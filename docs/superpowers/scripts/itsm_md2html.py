"""Render ITSM phase 2-6 specs to standalone HTML styled like the phase-1 HTML, plus an index page.

Usage: python3 docs/superpowers/scripts/itsm_md2html.py docs/superpowers/specs  (needs the `markdown` package)
The connected roadmap (diagram, hand-off table) comes from roadmap.py next to this file.
"""
import html
import pathlib
import re
import sys

import markdown

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import roadmap  # noqa: E402

SPECS = pathlib.Path(sys.argv[1])
BASE = SPECS / "2026-10-14-itsm-design.html"
CSS_BASE = re.search(r"<style>(.*?)</style>", BASE.read_text(encoding="utf-8"), re.S).group(1).split("/* extras */")[0]
CSS = CSS_BASE + "/* extras */" + """
  .phasebar { display: flex; flex-wrap: wrap; gap: 6px; margin: 14px 0 0; }
  .phasebar a { font-size: 12.5px; padding: 3px 10px; border-radius: 999px; color: #fff; text-decoration: none;
    background: rgba(255,255,255,.08); border: 1px solid rgba(255,255,255,.2); }
  .phasebar a[aria-current] { background: var(--accent); border-color: var(--accent); font-weight: 600; }
  .phasebar a:hover, .phasebar a:focus-visible { background: rgba(255,255,255,.2); outline: none; }
  .overview h3 { margin-top: 4px; }
  a.step { text-decoration: none; color: inherit; display: block; }
  a.step:hover, a.step:focus-visible { border-color: var(--accent); outline: none; }
  .state.reject { background: var(--bad-soft); border-color: #eab3b5; color: var(--bad); }
  .pager { display: flex; justify-content: space-between; gap: 12px; }
  .pager a { color: var(--accent); text-decoration: none; font-weight: 600; }
"""
CSS += roadmap.EXTRA_CSS

PHASES = roadmap.PHASES

# (label, kind) — kind: "" | "pause" | "closed" | "reject"; "|" starts a second branch line
FLOWS = {
    2: ("요청 상태 흐름", [("포털에서 항목 요청", ""), ("승인 대기 · SLA 멈춤", "pause"), ("접수", ""), ("처리 중", ""), ("해결", "closed")],
        [("승인 대기", "pause"), ("반려 (한 명이라도 반려)", "reject")]),
    3: ("문제 상태 흐름", [("등록", ""), ("분석 중", ""), ("알려진 오류", "pause"), ("해결", "closed"), ("종료", "closed")],
        [("문제 해결", "closed"), ("연결된 장애 일괄 해결 (처리자가 고름)", "")]),
    4: ("CI 상태", [("planned", ""), ("active", ""), ("maintenance", "pause"), ("retired", "closed")],
        [("전체 동기화에서 빠짐", ""), ("unverified (지우지 않음)", "pause")]),
    5: ("변경 상태 흐름", [("작성", ""), ("검토", ""), ("승인 대기", "pause"), ("예정", ""), ("실행 중", ""), ("완료", ""), ("사후 검토", ""), ("종료", "closed")],
        [("표준 변경 (템플릿)", ""), ("예정 (승인 없음)", "")]),
    6: ("문서 상태 흐름", [("초안", ""), ("검토", "pause"), ("게시", ""), ("보관", "closed")],
        [("검토", "pause"), ("초안으로 돌려보내기 (사유)", "reject")]),
}


def flow_html(items):
    parts = []
    for i, (label, kind) in enumerate(items):
        if i:
            parts.append('<span class="arrow" aria-hidden="true">→</span>')
        parts.append(f'<span class="state {kind}">{html.escape(label)}</span>')
    return '<div class="flow">' + "".join(parts) + "</div>"


def roadmap_html(current, linked=True):
    cells = []
    for no, name, sub, slug in PHASES:
        cls = "step now" if no == current else "step"
        label = f"{no}단계" + (" · 이 문서" if no == current else "")
        inner = f"<b>{label}</b><span>{html.escape(name)}</span><br>{html.escape(sub)}"
        if linked and no != current:
            cells.append(f'<a class="{cls}" role="listitem" href="{slug}.html">{inner}</a>')
        else:
            cells.append(f'<div class="{cls}" role="listitem">{inner}</div>')
    return '<div class="roadmap" role="list">' + "".join(cells) + "</div>"


def phasebar(current):
    links = ['<a href="2026-10-14-itsm-index.html"' + (' aria-current="page"' if current == 0 else "") + ">전체</a>"]
    for no, name, _, slug in PHASES:
        cur = ' aria-current="page"' if no == current else ""
        links.append(f'<a href="{slug}.html"{cur}>{no} {html.escape(name)}</a>')
    return '<nav class="phasebar" aria-label="ITSM 단계">' + "".join(links) + "</nav>"


def page(title, chips, current, toc, body):
    chip_html = "".join(f'<span class="chip">{html.escape(c)}</span>' for c in chips)
    toc_html = "".join(f'<a href="#{i}">{html.escape(t)}</a>' for i, t in toc)
    return f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)} | openPMS</title>
<style>{CSS}</style>
</head>
<body>
<header class="top">
  <div class="brand">openPMS · ITSM 설계서</div>
  <h1>{html.escape(title)}</h1>
  <div class="meta">{chip_html}</div>
  {phasebar(current)}
</header>
<div class="layout">
<nav class="toc" aria-label="목차">
  <strong>목차</strong>
  {toc_html}
</nav>
<main>
{body}
</main>
</div>
<footer>openPMS 개발팀 · 사내 전용</footer>
</body>
</html>
"""


def render_phase(no, slug):
    src = (SPECS / f"{slug}.md").read_text(encoding="utf-8")
    lines = src.splitlines()
    title = lines[0].lstrip("# ").strip()
    # metadata bullets right after the title
    i, chips = 1, []
    while i < len(lines) and (not lines[i].strip() or lines[i].startswith("- ")):
        if lines[i].startswith("- "):
            chips.append(re.sub(r"`([^`]*)`", r"\1", lines[i][2:].strip()))
        i += 1
    # python-markdown nests lists only at 4-space indents; the specs use 2/3 spaces
    body_md = "\n".join(re.sub(r"^ {1,3}(?=(?:[-*]|\d+\.) )", "    ", ln) for ln in lines[i:])
    body = markdown.markdown(body_md, extensions=["tables", "sane_lists"])
    # wrap every h2 into a section and number it
    chunks = re.split(r"(?=<h2>)", body)
    sections, toc = [], []
    for chunk in chunks:
        m = re.match(r"<h2>(\d+)\.\s*(.*?)</h2>", chunk, re.S)
        if not m:
            if chunk.strip():
                sections.append(f"<section>{chunk}</section>")
            continue
        num, text = m.group(1), m.group(2)
        sid = f"s{num}"
        plain = re.sub(r"<[^>]+>", "", text)
        toc.append((sid, f"{num}. {plain}"))
        rest = chunk[m.end():]
        extra = ""
        if num == "1":
            ftitle, main_flow, branch = FLOWS[no]
            extra = (f'<div class="overview"><h3>로드맵에서의 위치</h3>{roadmap.diagram(no)}{roadmap.handoff_callouts(no)}'
                     f"<h3>{html.escape(ftitle)}</h3>{flow_html(main_flow)}{flow_html(branch)}</div>")
        sections.append(f'<section id="{sid}"><h2><span class="no">{num}</span>{text}</h2>{extra}{rest}</section>')
    prev_next = []
    if no > 1:
        p = PHASES[no - 2]
        prev_next.append(f'<a href="{p[3]}.html">← {p[0]}단계 {html.escape(p[1])}</a>')
    else:
        prev_next.append("<span></span>")
    if no < 6:
        n = PHASES[no]
        prev_next.append(f'<a href="{n[3]}.html">{n[0]}단계 {html.escape(n[1])} →</a>')
    sections.append('<section class="pager">' + "".join(prev_next) + "</section>")
    body_html = "\n".join(sections)
    # tables: give the generated tables the same look; colour-code nothing else
    out = page(title, chips + [f"원문 {slug}.md"], no, toc, body_html)
    (SPECS / f"{slug}.html").write_text(out, encoding="utf-8")
    return title


def render_index(titles):
    rows = "".join(
        f'<tr><td>{no}</td><td><a href="{slug}.html">{html.escape(titles.get(no, name))}</a></td><td>{html.escape(sub)}</td></tr>'
        for no, name, sub, slug in PHASES
    )
    body = f"""
<section id="s1"><h2><span class="no">1</span>로드맵</h2>
{roadmap.diagram(0)}
<h3>단계 사이에 오가는 것</h3>
{roadmap.handoff_table(0)}
<div class="callout">ITIL 전체 프로세스를 6단계로 나누어 단계마다 설계서 → 구현 계획 → 구현을 돈다. CI 기반 충돌 확인을 위해 CMDB(4)를 변경(5)보다 먼저 만든다(2026-10-14 결정).</div>
</section>
<section id="s2"><h2><span class="no">2</span>설계서</h2>
<table><thead><tr><th style="width:60px">단계</th><th>설계서</th><th>핵심</th></tr></thead><tbody>{rows}</tbody></table>
</section>
<section id="s3"><h2><span class="no">3</span>공통 틀</h2>
<ul>
<li>장애·요청·문제·변경은 작업 패키지 <b>유형</b> + 1:1 <b>확장 테이블</b>로 만든다. 목록·필터·보드·저널·알림·웹훅·첨부·워크플로를 그대로 쓴다</li>
<li>CMDB의 구성 항목과 지식 베이스 문서는 작업이 아니므로 별도 테이블로 둔다</li>
<li>멤버가 아닌 직원의 진입로(포털, 내 승인함)는 별도 컨트롤러로 두고, 본인 것만 보이며 남의 것은 404</li>
<li>승인 엔진(<code>ticket_approvals</code>)은 2단계에서 만들고 5단계 CAB 승인에서 다시 쓴다</li>
<li>스키마 V20–V25, 개발 데이터 V1004–V1009(순서대로 만든다는 가정의 예정 번호)</li>
</ul>
</section>"""
    out = page("ITSM 설계서 모음 — 로드맵과 단계별 설계", ["날짜 2026-10-14", "상태: 설계 합의(구현 계획 전)", "6단계"],
               0, [("s1", "1. 로드맵"), ("s2", "2. 설계서"), ("s3", "3. 공통 틀")], body)
    (SPECS / "2026-10-14-itsm-index.html").write_text(out, encoding="utf-8")


def patch_phase1():
    path = SPECS / "2026-10-14-itsm-design.html"
    t = path.read_text(encoding="utf-8")
    t = re.sub(r"<style>.*?</style>", lambda m: "<style>" + CSS + "</style>", t, count=1, flags=re.S)
    bar = "<!-- phasebar:start -->" + phasebar(1) + "<!-- phasebar:end -->"
    if "<!-- phasebar:start -->" in t:
        t = re.sub(r"<!-- phasebar:start -->.*?<!-- phasebar:end -->", lambda m: bar, t, flags=re.S)
    else:
        t = t.replace("</div>\n</header>", "</div>\n  " + bar + "\n</header>", 1)
    rm = ("<!-- roadmap:start -->" + roadmap.diagram(1) + "<h3>단계 사이에 오가는 것</h3>"
          + roadmap.handoff_table(1) + "<!-- roadmap:end -->")
    if "<!-- roadmap:start -->" in t:
        t = re.sub(r"<!-- roadmap:start -->.*?<!-- roadmap:end -->", lambda m: rm, t, flags=re.S)
    else:
        t = re.sub(r'(<h2><span class="no">2</span>로드맵</h2>).*?(?=<div class="callout">)',
                   lambda m: m.group(1) + "\n  " + rm + "\n  ", t, count=1, flags=re.S)
    path.write_text(t, encoding="utf-8")


titles = {1: "장애·서비스 요청 (기반)"}
for no, _, _, slug in PHASES[1:]:
    titles[no] = render_phase(no, slug)
render_index(titles)
patch_phase1()
print("ok", sorted(titles))
