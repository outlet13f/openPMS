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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GitLab: webhooks carry the secret in X-Gitlab-Token; polling uses REST API v4 with PRIVATE-TOKEN. */
@Component
public class GitLabAdapter implements CicdAdapter {
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{7,64}");
    private static final Pattern FULL_SHA = Pattern.compile("[0-9a-f]{40}");

    private final CicdHttp http;
    private final JsonMapper json;

    public GitLabAdapter(CicdHttp http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override public String kind() { return "gitlab"; }

    @Override public boolean verify(Map<String, String> headers, byte[] body, String secret) {
        return same(headers.get("x-gitlab-token"), secret);
    }

    @Override public List<CicdEvent> parse(Map<String, String> headers, byte[] body) {
        var event = headers.get("x-gitlab-event");
        if (event == null) return List.of();
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            return List.of();
        }
        var repo = text(root.path("project").path("id"));
        if (repo.isEmpty()) return List.of();
        switch (event) {
            case "Push Hook" -> {
                var out = new ArrayList<CicdEvent>();
                for (var c : root.path("commits")) {
                    var sha = text(c.path("id"));
                    if (sha.isEmpty()) continue;
                    out.add(commit(repo, sha, text(c.path("title")), text(c.path("message")), text(c.path("author").path("name")),
                            text(c.path("author").path("email")), time(text(c.path("timestamp"))), text(c.path("url"))));
                }
                return out;
            }
            case "Merge Request Hook" -> {
                var a = root.path("object_attributes");
                var iid = number(a.path("iid"));
                if (iid == 0) return List.of();
                // "user" is whoever caused the event; only on "open" is that the author (an update by a reviewer is not)
                boolean opened = text(a.path("action")).equals("open");
                return List.of(mergeRequest(repo, a, opened ? text(root.path("user").path("name")) : "",
                        opened ? text(root.path("user").path("email")) : ""));
            }
            case "Pipeline Hook" -> {
                var a = root.path("object_attributes");
                var started = time(text(a.path("created_at")));
                var finished = time(text(a.path("finished_at")));
                var id = text(a.path("id"));
                var url = text(a.path("url"));
                if (url.isEmpty() && !id.isEmpty()) url = text(root.path("project").path("web_url")) + "/-/pipelines/" + id;
                return pipeline(repo, id, text(a.path("name")), text(a.path("sha")), text(a.path("ref")), text(a.path("status")), url,
                        started, finished, finished != null ? finished : started);
            }
            case "Deployment Hook" -> {
                var id = text(root.path("deployment_id"));
                var status = text(root.path("status"));
                var normalized = deploymentStatus(status);
                if (id.isEmpty() || normalized == null) return List.of();
                var commitUrl = text(root.path("commit_url"));
                var last = commitUrl.substring(commitUrl.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                var sha = FULL_SHA.matcher(last).matches() ? last : hexOrNull(text(root.path("short_sha")));
                var d = new CicdEvent.Deployment(id, sha, null, text(root.path("environment")), normalized,
                        text(root.path("deployable_url")), time(text(root.path("status_changed_at"))));
                return List.of(new CicdEvent("deployment", "deployment:" + repo + ":" + id + ":" + status + ":" + instant(d.deployedAt()), repo, null, null, null, d, null));
            }
            default -> {
                return List.of();
            }
        }
    }

    /** running | success | failed | canceled, or null for a state we do not know. */
    private static String pipelineStatus(String s) {
        return switch (s) {
            case "success" -> "success";
            case "failed" -> "failed";
            case "canceled", "skipped" -> "canceled";
            case "created", "waiting_for_resource", "preparing", "pending", "running", "scheduled", "manual" -> "running";
            default -> null;
        };
    }

    /** running | success | failed, or null for a state we do not know. */
    private static String deploymentStatus(String s) {
        return switch (s) {
            case "success" -> "success";
            case "running", "created" -> "running";
            case "failed", "canceled" -> "failed";
            default -> null;
        };
    }

    private static String hexOrNull(String sha) {
        var s = sha == null ? "" : sha.toLowerCase(Locale.ROOT);
        return HEX.matcher(s).matches() ? s : null;
    }

    /** No event for an unknown state, an empty id, or a sha that is not hex. */
    private static List<CicdEvent> pipeline(String repo, String id, String name, String sha, String ref, String rawStatus, String url,
                                            OffsetDateTime started, OffsetDateTime finished, OffsetDateTime updated) {
        var status = pipelineStatus(rawStatus);
        var lower = hexOrNull(sha);
        if (id.isEmpty() || status == null || lower == null) return List.of();
        var p = new CicdEvent.Pipeline(id, name, lower, ref, status, url, started, finished, updated);
        return List.of(new CicdEvent("pipeline", "pipeline:" + repo + ":" + id + ":" + rawStatus + ":" + instant(updated), repo, null, null, p, null, null));
    }

    private static CicdEvent commit(String repo, String sha, String title, String message, String name, String email,
                                    OffsetDateTime at, String url) {
        var t = title.isEmpty() ? title(message) : title(title);
        return new CicdEvent("commit", "commit:" + repo + ":" + sha, repo,
                new CicdEvent.Commit(sha, t, message, name, email, at, url), null);
    }

    /** attrs: a merge request as the webhook's object_attributes or the REST list shows it (same field names). */
    private static CicdEvent mergeRequest(String repo, JsonNode a, String authorName, String authorEmail) {
        var iid = number(a.path("iid"));
        var updated = time(text(a.path("updated_at")));
        var state = text(a.path("state"));
        if (state.equals("locked")) state = "closed";
        if (state.equals("opened") && (flag(a.path("draft")) || flag(a.path("work_in_progress")))) state = "draft";
        var url = text(a.path("url"));
        if (url.isEmpty()) url = text(a.path("web_url"));
        String mergeCommit = null;
        if (state.equals("merged")) {
            mergeCommit = hexOrNull(text(a.path("squash_commit_sha")));
            if (mergeCommit == null) mergeCommit = hexOrNull(text(a.path("merge_commit_sha")));
        }
        var mr = new CicdEvent.MergeRequest(iid, text(a.path("title")), text(a.path("description")), state,
                text(a.path("source_branch")), text(a.path("target_branch")), authorName, authorEmail,
                time(text(a.path("merged_at"))), updated, url, mergeCommit);
        return new CicdEvent("merge_request", "mr:" + repo + ":" + iid + ":" + instant(updated), repo, null, mr);
    }

    private Map<String, String> auth(Remote r) {
        return Map.of("PRIVATE-TOKEN", r.token() == null ? "" : r.token(), "Accept", "application/json");
    }

    private String api(Remote r) { return trimSlash(r.baseUrl()) + "/api/v4"; }

    private JsonNode getJson(Remote r, String path) throws IOException {
        var res = http.get(api(r) + path, auth(r));
        if (res.status() / 100 != 2) throw new IOException("unexpected status " + res.status());
        return readTree(res.body());
    }

    private JsonNode readTree(byte[] body) throws IOException {
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw new IOException("unreadable response");
        }
    }

    @Override public RemoteRepo repository(Remote remote, String externalId) throws IOException {
        var res = http.get(api(remote) + "/projects/" + enc(externalId), auth(remote));
        if (res.status() == 404) return null;
        if (res.status() / 100 != 2) throw new IOException("unexpected status " + res.status());
        JsonNode p;
        try {
            p = json.readTree(res.body());
        } catch (JacksonException e) {
            throw new IOException("unreadable response");
        }
        var id = text(p.path("id"));
        if (id.isEmpty()) return null;
        return new RemoteRepo(id, text(p.path("path_with_namespace")), text(p.path("web_url")));
    }

    @Override public List<RemoteRepo> listRepositories(Remote remote, String query) throws IOException {
        var q = query == null || query.isBlank() ? "" : "search=" + enc(query.strip()) + "&";
        var out = new ArrayList<RemoteRepo>();
        for (var p : getJson(remote, "/projects?" + q + "membership=true&per_page=20")) {
            out.add(new RemoteRepo(text(p.path("id")), text(p.path("path_with_namespace")), text(p.path("web_url"))));
        }
        return out;
    }

    @Override public Polled poll(Remote remote, String repoExternalId, OffsetDateTime since) throws IOException {
        var id = enc(repoExternalId);
        var out = new ArrayList<CicdEvent>();
        boolean truncated = false;
        var commitsQuery = since == null ? "" : "since=" + enc(since.toInstant().toString()) + "&";
        for (int page = 1; ; page++) {
            var items = getJson(remote, "/projects/" + id + "/repository/commits?" + commitsQuery + "per_page=" + PAGE_SIZE + "&page=" + page);
            for (var c : items) {
                var sha = text(c.path("id"));
                if (sha.isEmpty()) continue;
                out.add(commit(repoExternalId, sha, text(c.path("title")), text(c.path("message")), text(c.path("author_name")),
                        text(c.path("author_email")), time(text(c.path("committed_date"))), text(c.path("web_url"))));
            }
            if (items.size() < PAGE_SIZE) break;
            if (page == MAX_PAGES) { truncated = true; break; }
        }
        var mrQuery = since == null ? "" : "updated_after=" + enc(since.toInstant().toString()) + "&";
        for (int page = 1; ; page++) {
            var items = getJson(remote, "/projects/" + id + "/merge_requests?" + mrQuery + "per_page=" + PAGE_SIZE + "&page=" + page);
            for (var m : items) {
                if (number(m.path("iid")) == 0) continue;
                var author = text(m.path("author").path("name"));
                out.add(mergeRequest(repoExternalId, m, author.isEmpty() ? text(m.path("author").path("username")) : author, ""));
            }
            if (items.size() < PAGE_SIZE) break;
            if (page == MAX_PAGES) { truncated = true; break; }
        }
        var updatedQuery = since == null ? "" : "updated_after=" + enc(since.toInstant().toString()) + "&";
        var listQuery = updatedQuery + "order_by=updated_at&sort=desc&per_page=" + PAGE_SIZE + "&page=";
        for (int page = 1; ; page++) {
            var items = optionalList(remote, "/projects/" + id + "/pipelines?" + listQuery + page);
            for (var p : items) {
                var updated = time(text(p.path("updated_at")));
                var rawStatus = text(p.path("status"));
                var status = pipelineStatus(rawStatus);
                out.addAll(pipeline(repoExternalId, text(p.path("id")), text(p.path("name")), text(p.path("sha")), text(p.path("ref")), rawStatus,
                        text(p.path("web_url")), time(text(p.path("created_at"))),
                        "running".equals(status) ? null : updated, updated));
            }
            if (items.size() < PAGE_SIZE) break;
            if (page == MAX_PAGES) { truncated = true; break; }
        }
        for (int page = 1; ; page++) {
            var items = optionalList(remote, "/projects/" + id + "/deployments?" + listQuery + page);
            for (var d : items) {
                var depId = text(d.path("id"));
                var rawStatus = text(d.path("status"));
                var status = deploymentStatus(rawStatus);
                if (depId.isEmpty() || status == null) continue;
                var dep = new CicdEvent.Deployment(depId, hexOrNull(text(d.path("sha"))), null, text(d.path("environment").path("name")),
                        status, text(d.path("deployable").path("web_url")), time(text(d.path("updated_at"))));
                out.add(new CicdEvent("deployment", "deployment:" + repoExternalId + ":" + depId + ":" + rawStatus + ":" + instant(dep.deployedAt()), repoExternalId, null, null, null, dep, null));
            }
            if (items.size() < PAGE_SIZE) break;
            if (page == MAX_PAGES) { truncated = true; break; }
        }
        return new Polled(out, truncated);
    }

    /** A list the token may not read (403) or the project does not offer (404) counts as empty, so commits and merge requests still come through. */
    private JsonNode optionalList(Remote r, String path) throws IOException {
        try {
            return getJson(r, path);
        } catch (IOException e) {
            if ("unexpected status 403".equals(e.getMessage()) || "unexpected status 404".equals(e.getMessage())) return json.createArrayNode();
            throw e;
        }
    }

    private static List<CicdEvent.Commit> commitsOf(JsonNode items) {
        var out = new ArrayList<CicdEvent.Commit>();
        for (var c : items) {
            var sha = text(c.path("id"));
            if (sha.isEmpty()) continue;
            var message = text(c.path("message"));
            var t = text(c.path("title"));
            var at = time(text(c.path("committed_date")));
            if (at == null) at = time(text(c.path("created_at")));
            out.add(new CicdEvent.Commit(sha, t.isEmpty() ? title(message) : title(t), message, text(c.path("author_name")),
                    text(c.path("author_email")), at, text(c.path("web_url"))));
        }
        return out;
    }

    @Override public Comparison compare(Remote remote, String repoExternalId, String base, String head, int limit) throws IOException {
        var id = enc(repoExternalId);
        List<CicdEvent.Commit> found;
        boolean truncated = false;
        try {
            var res = http.get(api(remote) + "/projects/" + id + "/repository/compare?from=" + enc(base) + "&to=" + enc(head) + "&straight=false",
                    auth(remote), CicdHttp.MAX_COMPARE_BODY);
            if (res.status() / 100 != 2) throw new IOException("unexpected status " + res.status());
            found = commitsOf(readTree(res.body()).path("commits"));
        } catch (IOException e) {
            if (!"response too large".equals(e.getMessage())) throw e;
            // the diffs made it too big: the same range from the commit list, which carries no diffs
            found = new ArrayList<>();
            for (int page = 1; ; page++) {
                var items = getJson(remote, "/projects/" + id + "/repository/commits?ref_name=" + enc(base + ".." + head)
                        + "&per_page=" + PAGE_SIZE + "&page=" + page);
                found.addAll(commitsOf(items));
                if (items.size() < PAGE_SIZE) break;
                if (page == MAX_PAGES) { truncated = true; break; }
            }
        }
        found.sort(Comparator.comparing(CicdEvent.Commit::committedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (found.size() > limit) {
            found = new ArrayList<>(found.subList(0, limit));
            truncated = true;
        }
        return new Comparison(List.copyOf(found), truncated);
    }

    @Override public boolean contains(Remote remote, String repoExternalId, String head, String commit) throws IOException {
        var base = text(getJson(remote, "/projects/" + enc(repoExternalId) + "/repository/merge_base?refs%5B%5D=" + enc(commit)
                + "&refs%5B%5D=" + enc(head)).path("id")).toLowerCase(Locale.ROOT);
        var c = commit.toLowerCase(Locale.ROOT);
        return !base.isEmpty() && !c.isEmpty() && (base.startsWith(c) || c.startsWith(base));
    }

    @Override public int check(Remote remote) throws IOException {
        return http.get(api(remote) + "/user", auth(remote)).status();
    }
}
