package com.openpms.cicd;

import static com.openpms.cicd.AdapterSupport.enc;
import static com.openpms.cicd.AdapterSupport.flag;
import static com.openpms.cicd.AdapterSupport.instant;
import static com.openpms.cicd.AdapterSupport.number;
import static com.openpms.cicd.AdapterSupport.same;
import static com.openpms.cicd.AdapterSupport.text;
import static com.openpms.cicd.AdapterSupport.time;
import static com.openpms.cicd.AdapterSupport.title;
import static com.openpms.cicd.AdapterSupport.trimSlash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GitHub (Cloud and Enterprise Server): webhooks are signed (X-Hub-Signature-256); polling uses the REST API with a bearer token. */
@Component
public class GitHubAdapter implements CicdAdapter {
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{7,64}");
    /** Deployments whose latest status one poll run reads (one request each). */
    private static final int MAX_DEPLOYMENT_STATUS_READS = 30;

    private final CicdHttp http;
    private final JsonMapper json;

    public GitHubAdapter(CicdHttp http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override public String kind() { return "github"; }

    @Override public boolean verify(Map<String, String> headers, byte[] body, String secret) {
        var given = headers.get("x-hub-signature-256");
        if (given == null || secret == null) return false;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return same(given, "sha256=" + HexFormat.of().formatHex(mac.doFinal(body)));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override public List<CicdEvent> parse(Map<String, String> headers, byte[] body) {
        var event = headers.get("x-github-event");
        if (event == null || !(event.equals("push") || event.equals("pull_request") || event.equals("workflow_run")
                || event.equals("deployment_status"))) return List.of();
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            return List.of();
        }
        var repo = text(root.path("repository").path("id"));
        if (repo.isEmpty()) return List.of();
        if (event.equals("workflow_run")) return pipeline(repo, root.path("workflow_run"));
        if (event.equals("deployment_status")) {
            return deployment(repo, root.path("deployment"), root.path("deployment_status"));
        }
        if (event.equals("push")) {
            var out = new ArrayList<CicdEvent>();
            for (var c : root.path("commits")) {
                var sha = text(c.path("id"));
                if (sha.isEmpty()) continue;
                out.add(commit(repo, sha, text(c.path("message")), text(c.path("author").path("name")),
                        text(c.path("author").path("email")), time(text(c.path("timestamp"))), text(c.path("url"))));
            }
            return out;
        }
        var pr = root.path("pull_request");
        if (number(pr.path("number")) == 0) return List.of();
        return List.of(pull(repo, pr));
    }

    /** running | success | failed | canceled, or null for a finished run whose conclusion we do not know. */
    private static String runStatus(String status, String conclusion) {
        if (!status.equals("completed")) return "running";
        return switch (conclusion) {
            case "success" -> "success";
            case "failure", "timed_out", "startup_failure", "action_required" -> "failed";
            case "cancelled", "skipped", "stale", "neutral" -> "canceled";
            default -> null;
        };
    }

    /** running | success | failed, or null (inactive and states we do not know give no event). */
    private static String deploymentState(String state) {
        return switch (state) {
            case "success" -> "success";
            case "failure", "error" -> "failed";
            case "pending", "queued", "in_progress" -> "running";
            default -> null;
        };
    }

    private static String hexOrNull(String sha) {
        var s = sha == null ? "" : sha.toLowerCase(Locale.ROOT);
        return HEX.matcher(s).matches() ? s : null;
    }

    /** run: a workflow run as the webhook or the REST list shows it (same field names). No event without an id, a hex sha or a known state. */
    private static List<CicdEvent> pipeline(String repo, JsonNode run) {
        var id = text(run.path("id"));
        var status = runStatus(text(run.path("status")), text(run.path("conclusion")));
        var sha = hexOrNull(text(run.path("head_sha")));
        if (id.isEmpty() || status == null || sha == null) return List.of();
        var updated = time(text(run.path("updated_at")));
        var attempt = Math.max(1, number(run.path("run_attempt")));
        var p = new CicdEvent.Pipeline(id, text(run.path("name")), sha, text(run.path("head_branch")), status, text(run.path("html_url")),
                time(text(run.path("run_started_at"))), status.equals("running") ? null : updated, updated);
        return List.of(new CicdEvent("pipeline", "pipeline:" + repo + ":" + id + ":" + status + ":" + attempt + ":" + instant(updated),
                repo, null, null, p, null, null));
    }

    /** deployment and status as the webhook or the REST lists show them (same field names). */
    private static List<CicdEvent> deployment(String repo, JsonNode deployment, JsonNode status) {
        var id = text(deployment.path("id"));
        var statusId = text(status.path("id"));
        var state = deploymentState(text(status.path("state")));
        if (id.isEmpty() || statusId.isEmpty() || state == null) return List.of();
        var environment = text(status.path("environment"));
        if (environment.isEmpty()) environment = text(deployment.path("environment"));
        var url = text(status.path("log_url"));
        if (url.isEmpty()) url = text(status.path("target_url"));
        var at = time(text(status.path("created_at")));
        var d = new CicdEvent.Deployment(id, hexOrNull(text(deployment.path("sha"))), null, environment, state, url, at);
        return List.of(new CicdEvent("deployment", "deployment:" + repo + ":" + id + ":" + statusId + ":" + instant(at), repo, null, null, null, d, null));
    }

    private static CicdEvent commit(String repo, String sha, String message, String name, String email, OffsetDateTime at, String url) {
        return new CicdEvent("commit", "commit:" + repo + ":" + sha, repo,
                new CicdEvent.Commit(sha, title(message), message, name, email, at, url), null);
    }

    /** pr: a pull request as the webhook or the REST list shows it (same field names). */
    private static CicdEvent pull(String repo, JsonNode pr) {
        var number = number(pr.path("number"));
        var updated = time(text(pr.path("updated_at")));
        var merged = time(text(pr.path("merged_at")));
        var state = text(pr.path("state"));
        if (state.equals("closed") && merged != null) state = "merged";
        else if (state.equals("open") && flag(pr.path("draft"))) state = "draft";
        var mr = new CicdEvent.MergeRequest(number, text(pr.path("title")), text(pr.path("body")), state,
                text(pr.path("head").path("ref")), text(pr.path("base").path("ref")), text(pr.path("user").path("login")), "",
                merged, updated, text(pr.path("html_url")), state.equals("merged") ? hexOrNull(text(pr.path("merge_commit_sha"))) : null);
        return new CicdEvent("merge_request", "mr:" + repo + ":" + number + ":" + instant(updated), repo, null, mr);
    }

    private Map<String, String> auth(Remote r) {
        return Map.of("Authorization", "Bearer " + (r.token() == null ? "" : r.token()), "Accept", "application/vnd.github+json");
    }

    /** github.com's API lives on its own host; an Enterprise Server serves it under /api/v3. */
    static String api(Remote r) {
        var base = trimSlash(r.baseUrl());
        return base.equalsIgnoreCase("https://github.com") ? "https://api.github.com" : base + "/api/v3";
    }

    private JsonNode getJson(Remote r, String path) throws IOException {
        return readOk(http.get(api(r) + path, auth(r)));
    }

    private JsonNode getJson(Remote r, String path, int maxBytes) throws IOException {
        return readOk(http.get(api(r) + path, auth(r), maxBytes));
    }

    private JsonNode readOk(CicdHttp.HttpResult res) throws IOException {
        if (res.status() / 100 != 2) throw new UnexpectedStatus(res.status());
        try {
            return json.readTree(res.body());
        } catch (JacksonException e) {
            throw new IOException("unreadable response");
        }
    }

    @Override public RemoteRepo repository(Remote remote, String externalId) throws IOException {
        var res = http.get(api(remote) + "/repositories/" + enc(externalId), auth(remote));
        if (res.status() == 404) return null;
        if (res.status() / 100 != 2) throw new UnexpectedStatus(res.status());
        JsonNode p;
        try {
            p = json.readTree(res.body());
        } catch (JacksonException e) {
            throw new IOException("unreadable response");
        }
        var id = text(p.path("id"));
        if (id.isEmpty()) return null;
        return new RemoteRepo(id, text(p.path("full_name")), text(p.path("html_url")));
    }

    @Override public List<RemoteRepo> listRepositories(Remote remote, String query) throws IOException {
        var q = query == null ? "" : query.strip().toLowerCase();
        var out = new ArrayList<RemoteRepo>();
        // /user/repos has no name filter: walk up to 10 pages of 100, stop at 20 matches or a short page
        for (int page = 1; page <= MAX_PAGES; page++) {
            var items = getJson(remote, "/user/repos?per_page=" + PAGE_SIZE + "&page=" + page);
            for (var p : items) {
                var name = text(p.path("full_name"));
                if (!name.toLowerCase().contains(q)) continue;
                out.add(new RemoteRepo(text(p.path("id")), name, text(p.path("html_url"))));
                if (out.size() == 20) return out;
            }
            if (items.size() < PAGE_SIZE) break;
        }
        return out;
    }

    @Override public Polled poll(Remote remote, String repoExternalId, OffsetDateTime since) throws IOException {
        var id = enc(repoExternalId);
        var out = new ArrayList<CicdEvent>();
        boolean truncated = false;
        var sinceQuery = since == null ? "" : "since=" + enc(since.toInstant().toString()) + "&";
        for (int page = 1; ; page++) {
            var items = getJson(remote, "/repositories/" + id + "/commits?" + sinceQuery + "per_page=" + PAGE_SIZE + "&page=" + page);
            for (var c : items) {
                var sha = text(c.path("sha"));
                if (sha.isEmpty()) continue;
                var meta = c.path("commit");
                out.add(commit(repoExternalId, sha, text(meta.path("message")), text(meta.path("author").path("name")),
                        text(meta.path("author").path("email")), time(text(meta.path("author").path("date"))), text(c.path("html_url"))));
            }
            if (items.size() < PAGE_SIZE) break;
            if (page == MAX_PAGES) { truncated = true; break; }
        }
        pulls:
        for (int page = 1; ; page++) {
            var items = getJson(remote, "/repositories/" + id + "/pulls?state=all&sort=updated&direction=desc&per_page=" + PAGE_SIZE + "&page=" + page);
            for (var p : items) {
                var updated = time(text(p.path("updated_at")));
                if (since != null && updated != null && updated.isBefore(since)) break pulls; // newest first: the rest are older
                if (number(p.path("number")) == 0) continue;
                out.add(pull(repoExternalId, p));
            }
            if (items.size() < PAGE_SIZE) break;
            if (page == MAX_PAGES) { truncated = true; break; }
        }
        // runs are listed by creation time, so one that started before since and ended after it needs the one-day margin
        var lookBack = since == null ? null : since.toInstant().minus(java.time.Duration.ofDays(1));
        var createdQuery = lookBack == null ? "" : "created=" + enc(">=" + lookBack) + "&";
        for (int page = 1; ; page++) {
            var items = optionalList(remote, "/repositories/" + id + "/actions/runs?" + createdQuery + "per_page=" + PAGE_SIZE + "&page=" + page).path("workflow_runs");
            for (var run : items) out.addAll(pipeline(repoExternalId, run));
            if (items.size() < PAGE_SIZE) break;
            if (page == MAX_PAGES) { truncated = true; break; }
        }
        int statusReads = 0;
        deployments:
        for (int page = 1; ; page++) {
            var items = optionalList(remote, "/repositories/" + id + "/deployments?per_page=" + PAGE_SIZE + "&page=" + page);
            for (var d : items) {
                var created = time(text(d.path("created_at")));
                if (lookBack != null && created != null && created.toInstant().isBefore(lookBack)) break deployments;   // newest first: the rest are older
                var depId = text(d.path("id"));
                if (depId.isEmpty()) continue;
                var touched = time(text(d.path("updated_at")));
                if (since != null && touched != null && touched.isBefore(since)) continue;   // nothing new since the last poll: no status read
                if (statusReads == MAX_DEPLOYMENT_STATUS_READS) { truncated = true; break deployments; }
                statusReads++;
                // only the latest status counts (an older one never overrides it)
                var latest = optionalList(remote, "/repositories/" + id + "/deployments/" + enc(depId) + "/statuses?per_page=1");
                if (!latest.isArray() || latest.size() == 0) continue;
                out.addAll(deployment(repoExternalId, d, latest.get(0)));
            }
            if (items.size() < PAGE_SIZE) break;
            if (page == MAX_PAGES) { truncated = true; break; }
        }
        return new Polled(out, truncated);
    }

    private JsonNode optionalList(Remote r, String path) throws IOException {
        return AdapterSupport.optionalList(() -> getJson(r, path));
    }

    private static CicdEvent.Commit commitOf(JsonNode c) {
        var meta = c.path("commit");
        var author = meta.path("author");
        var at = time(text(author.path("date")));
        if (at == null) at = time(text(meta.path("committer").path("date")));
        var message = text(meta.path("message"));
        return new CicdEvent.Commit(text(c.path("sha")), title(message), message, text(author.path("name")), text(author.path("email")),
                at, text(c.path("html_url")));
    }

    private static String compareSpec(String base, String head) {
        return enc(base) + "..." + enc(head);
    }

    private JsonNode compareJson(Remote remote, String path) throws IOException {
        return getJson(remote, path, CicdHttp.MAX_COMPARE_BODY);
    }

    /** GitHub lists a comparison's commits oldest first: page 1 tells the total, then only the pages holding the newest ones are read. */
    @Override public Comparison compare(Remote remote, String repoExternalId, String base, String head, int limit) throws IOException {
        var path = "/repositories/" + enc(repoExternalId) + "/compare/" + compareSpec(base, head) + "?per_page=" + PAGE_SIZE + "&page=";
        var first = compareJson(remote, path + 1);
        var firstItems = first.path("commits");
        long total = first.path("total_commits").isNumber() ? first.path("total_commits").longValue() : firstItems.size();
        total = Math.max(total, firstItems.size());
        int keep = (int) Math.max(0, Math.min(Math.min(limit, (long) MAX_PAGES * PAGE_SIZE), total));
        int firstPage = (int) ((total - keep) / PAGE_SIZE) + 1;
        int lastPage = (int) ((total + PAGE_SIZE - 1) / PAGE_SIZE);
        var found = new ArrayList<CicdEvent.Commit>();
        for (int page = firstPage; page <= lastPage; page++) {
            var items = page == 1 ? firstItems : compareJson(remote, path + page).path("commits");
            for (var c : items) {
                if (!text(c.path("sha")).isEmpty()) found.add(commitOf(c));
            }
            if (items.size() < PAGE_SIZE) break;
        }
        var newest = found.size() > keep ? found.subList(found.size() - keep, found.size()) : found;
        var sorted = new ArrayList<>(newest);
        sorted.sort(Comparator.comparing(CicdEvent.Commit::committedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return new Comparison(List.copyOf(sorted), total > keep);
    }

    /** A 404 (the commit is gone, e.g. after a force push) or 422 (no common ancestor, unknown commit) answers "not contained". */
    @Override public boolean contains(Remote remote, String repoExternalId, String head, String commit) throws IOException {
        var res = http.get(api(remote) + "/repositories/" + enc(repoExternalId) + "/compare/" + compareSpec(commit, head) + "?per_page=1",
                auth(remote), CicdHttp.MAX_COMPARE_BODY);
        if (res.status() == 404 || res.status() == 422) return false;
        var status = text(readOk(res).path("status"));
        return switch (status) {
            case "ahead", "identical" -> true;
            case "behind", "diverged" -> false;
            default -> throw new IOException("unreadable response");
        };
    }

    @Override public int check(Remote remote) throws IOException {
        return http.get(api(remote) + "/user", auth(remote)).status();
    }
}
