#!/usr/bin/env python3
"""Load check for the performance budget (replica/fixes.md 4): N people use the main screens of the 1,000-item project at once.

Each simulated person signs in with their own session and goes round the screens the way the browser asks for them:
the list (20 and 100 rows), the Gantt chart (200 rows), the status board (1,000 cards) and a work package's detail page
(the item, its activity, relations and watchers), with a pause between screens. It prints response times per screen, the
requests per second and, with --stats, the CPU and memory of the containers while it ran. Standard library only.

  # the 1,000-item project first: dev database: scripts/dev.sh perf-seed; a compose install: psql < src/test/resources/perf/perf-1000.sql
  OPENPMS_PERF_PASSWORD=... python3 scripts/perf_load.py --base http://localhost:8090 --email someone@example.com \\
      --users 20 --seconds 120 --stats openpms-app-1,openpms-db-1

The account must be able to see the project (perf-1000 by default). The password is read from OPENPMS_PERF_PASSWORD only,
never from the command line. Run it against a test installation, not the one people are working in.
"""
import argparse
import gzip
import http.cookiejar
import json
import os
import random
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

OPEN = {"v": 1, "filters": [{"field": "status", "op": "open", "values": []}],
        "columns": ["number", "subject", "type", "status", "assignee", "priority", "dueDate"],
        "sort": [{"field": "number", "dir": "asc"}], "groupBy": None, "hierarchy": True}
GANTT = {"v": 1, "filters": [], "columns": ["number", "subject", "startDate", "dueDate"], "sort": [{"field": "number", "dir": "asc"}],
         "groupBy": None, "hierarchy": True, "timeline": {"visible": True, "zoom": "auto"}}


class Session:
    """One signed-in person: their own cookies (session and CSRF token)."""

    def __init__(self, base):
        self.base = base.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def xsrf(self):
        return next((c.value for c in self.jar if c.name == "XSRF-TOKEN"), "")

    def call(self, method, path, body=None):
        """Returns (status, seconds, bytes on the wire, parsed JSON or None)."""
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + "/api/v1" + path, data=data, method=method)
        req.add_header("Accept", "application/json")
        req.add_header("Accept-Encoding", "gzip")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if method != "GET":
            req.add_header("X-XSRF-TOKEN", self.xsrf())
        t0 = time.perf_counter()
        try:
            with self.opener.open(req, timeout=60) as r:
                raw = r.read()
                status, enc, retry = r.status, r.headers.get("Content-Encoding"), None
        except urllib.error.HTTPError as e:
            raw, status, enc, retry = e.read(), e.code, e.headers.get("Content-Encoding"), e.headers.get("Retry-After")
        took = time.perf_counter() - t0
        text = gzip.decompress(raw) if enc == "gzip" else raw
        try:
            parsed = json.loads(text) if text else None
        except ValueError:
            parsed = None
        if status == 429:
            parsed = {"retryAfter": int(retry or 5)}
        return status, took, len(raw), parsed

    def sign_in(self, email, password):
        self.call("GET", "/auth/me")  # the CSRF cookie
        for _ in range(10):
            status, _, _, body = self.call("POST", "/auth/login", {"email": email, "password": password})
            if status == 200:
                return
            if status == 429:  # sign-in is rate limited per address: wait as told
                time.sleep(body["retryAfter"])
                continue
            raise SystemExit(f"sign-in failed: HTTP {status}")
        raise SystemExit("sign-in kept being rate limited")


def percentile(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, max(0, int(round(p / 100 * len(xs) + 0.5)) - 1))]


def stats_sampler(engine, names, stop, samples):
    """Every 2 s: each container's memory and the CPU it used since the last look, in cores (podman: from its CPU time;
    docker: its CPU % / 100). podman's own CPU % is the average since the container started, so it is not used."""
    fmt = "{{.Name}}	{{.CPUNano}}	{{.MemUsage}}" if engine == "podman" else "{{.Name}}	{{.CPUPerc}}	{{.MemUsage}}"
    last = {}
    while not stop.is_set():
        try:
            out = subprocess.run([engine, "stats", "--no-stream", "--format", fmt, *names], capture_output=True, text=True, timeout=30).stdout
        except (OSError, subprocess.TimeoutExpired):
            return
        now = time.time()
        for line in out.strip().splitlines():
            parts = line.split("	")
            if len(parts) < 3:
                continue
            name, cpu, mem = parts[0], parts[1].rstrip("%"), parts[2].split("/")[0].strip()
            try:
                num = float("".join(ch for ch in mem if ch.isdigit() or ch == "."))
                unit = mem.lstrip("0123456789. ").upper()
                mib = num * {"B": 1 / 1048576, "KB": 1000 / 1048576, "KIB": 1 / 1024, "MB": 1e6 / 1048576, "MIB": 1, "GB": 1e9 / 1048576, "GIB": 1024}.get(unit, 1)
                if engine == "podman":
                    nano = int(cpu)
                    prev = last.get(name)
                    last[name] = (now, nano)
                    if prev is None:
                        continue
                    cores = (nano - prev[1]) / 1e9 / (now - prev[0])
                else:
                    cores = float(cpu) / 100
            except ValueError:
                continue
            samples.setdefault(name, []).append((cores, mib))
        stop.wait(2)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="the installation's address, e.g. http://localhost:8090")
    ap.add_argument("--email", required=True, help="an account that can see the project; password in OPENPMS_PERF_PASSWORD")
    ap.add_argument("--project", default="perf-1000")
    ap.add_argument("--users", type=int, default=20, help="people at once (default 20)")
    ap.add_argument("--seconds", type=int, default=60)
    ap.add_argument("--think", type=float, default=2.0, help="average pause between screens in seconds (default 2; 0 = as fast as possible)")
    ap.add_argument("--ramp", type=float, default=None, help="people start spread over this many seconds (default: --think)")
    ap.add_argument("--stats", default="", help="container names to watch, comma separated (docker/podman stats)")
    ap.add_argument("--engine", default="podman", help="docker or podman (for --stats)")
    a = ap.parse_args()
    password = os.environ.get("OPENPMS_PERF_PASSWORD")
    if not password:
        raise SystemExit("set OPENPMS_PERF_PASSWORD")

    first = Session(a.base)
    first.sign_in(a.email, password)
    status, _, _, project = first.call("GET", f"/projects/{a.project}")
    if status != 200:
        raise SystemExit(f"project {a.project}: HTTP {status} (run the perf seed first)")
    pid = project["id"]
    _, _, _, boards = first.call("GET", f"/projects/{pid}/boards")
    board = next(b["id"] for b in boards if b["kind"] == "status")
    found = first.call("POST", "/work-packages/query", {"projectId": pid, "page": 1, "pageSize": 1,
                                                        "spec": {**OPEN, "filters": [], "hierarchy": False, "text": "상세 화면 측정용 작업"}})[3]
    detail = found["rows"][0]["wp"]["number"]

    screens = {
        "목록 20건": [("POST", "/work-packages/query", {"projectId": pid, "page": 1, "pageSize": 20, "spec": OPEN}),
                    ("GET", f"/work-packages/filters?projectId={pid}", None)],
        "목록 100건": [("POST", "/work-packages/query", {"projectId": pid, "page": 1, "pageSize": 100, "spec": OPEN})],
        "Gantt 200건": [("POST", "/work-packages/query", {"projectId": pid, "page": 1, "pageSize": 200, "spec": GANTT})],
        "보드 1,000장": [("GET", f"/boards/{board}", None)],
        "작업 상세": [("GET", f"/work-packages/{detail}", None), ("GET", f"/work-packages/{detail}/activities", None),
                  ("GET", f"/work-packages/{detail}/relations", None), ("GET", f"/work-packages/{detail}/watchers", None)],
    }
    results = {k: [] for k in screens}
    wire = {k: 0 for k in screens}
    errors = {k: 0 for k in screens}
    lock = threading.Lock()
    deadline = [0.0]
    # the clock starts when the last person is signed in (the action runs before anyone is let through)
    ready = threading.Barrier(a.users + 1, action=lambda: deadline.__setitem__(0, time.time() + a.seconds))

    def person(i):
        s = Session(a.base)
        s.sign_in(a.email, password)
        ready.wait()  # everyone signed in: the clock starts together
        names = list(screens)
        rnd = random.Random(i)
        k = i % len(names)
        time.sleep(rnd.uniform(0, a.think if a.ramp is None else a.ramp))  # not everyone clicks in the same instant
        while time.time() < deadline[0]:
            name = names[k % len(names)]
            k += 1
            total, size, ok = 0.0, 0, True
            for method, path, body in screens[name]:
                status, took, n, _ = s.call(method, path, body)
                total += took
                size += n
                ok = ok and 200 <= status < 300
            with lock:
                if ok:
                    results[name].append(total)
                    wire[name] += size
                else:
                    errors[name] += 1
            if a.think > 0:
                time.sleep(rnd.uniform(0.5, 1.5) * a.think)

    print(f"{a.users}명 로그인 중…", file=sys.stderr)
    people = [threading.Thread(target=person, args=(i,), daemon=True) for i in range(a.users)]
    for t in people:
        t.start()
    ready.wait()
    t0 = deadline[0] - a.seconds
    stop = threading.Event()
    samples = {}
    sampler = None
    if a.stats:
        sampler = threading.Thread(target=stats_sampler, args=(a.engine, a.stats.split(","), stop, samples), daemon=True)
        sampler.start()
    for t in people:
        t.join()
    elapsed = time.time() - t0
    stop.set()
    if sampler:
        sampler.join(timeout=35)

    count = sum(len(v) for v in results.values())
    requests = sum(len(v) * len(screens[k]) for k, v in results.items())
    print(f"\n동시 사용자 {a.users}명, {elapsed:.0f}초, 화면 사이 평균 {a.think:g}초 쉼 — 화면 {count}번({count / elapsed:.1f}/초), 요청 {requests}개({requests / elapsed:.1f}/초)\n")
    print("| 화면 | 횟수 | 중앙값 | p95 | p99 | 최댓값 | 화면당 전송량 | 오류 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|")
    for name, xs in results.items():
        if not xs:
            print(f"| {name} | 0 | | | | | | {errors[name]} |")
            continue
        ms = [x * 1000 for x in xs]
        print(f"| {name} | {len(xs)} | {statistics.median(ms):.0f} ms | {percentile(ms, 95):.0f} ms | {percentile(ms, 99):.0f} ms | {max(ms):.0f} ms | "
              f"{wire[name] / len(xs) / 1024:.0f} KB | {errors[name]} |")
    if samples:
        print("\n| 컨테이너 | CPU 평균 | CPU 최대 | 메모리 최대 |")
        print("|---|---:|---:|---:|")
        for name, xs in samples.items():
            cpus = [c for c, _ in xs]
            print(f"| {name} | {statistics.mean(cpus):.2f}코어 | {max(cpus):.2f}코어 | {max(m for _, m in xs):.0f} MiB |")
    if any(errors.values()):
        sys.exit(1)


if __name__ == "__main__":
    main()
