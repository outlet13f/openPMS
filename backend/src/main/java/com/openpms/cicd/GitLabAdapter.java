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
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GitLab: webhooks carry the secret in X-Gitlab-Token; polling uses REST API v4 with PRIVATE-TOKEN. */
@Component
public class GitLabAdapter implements CicdAdapter {
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
                return List.of(mergeRequest(repo, a, text(root.path("user").path("name")), text(root.path("user").path("email"))));
            }
            default -> {
                return List.of();
            }
        }
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
        var mr = new CicdEvent.MergeRequest(iid, text(a.path("title")), text(a.path("description")), state,
                text(a.path("source_branch")), text(a.path("target_branch")), authorName, authorEmail,
                time(text(a.path("merged_at"))), updated, url);
        return new CicdEvent("merge_request", "mr:" + repo + ":" + iid + ":" + instant(updated), repo, null, mr);
    }

    private Map<String, String> auth(Remote r) {
        return Map.of("PRIVATE-TOKEN", r.token() == null ? "" : r.token(), "Accept", "application/json");
    }

    private String api(Remote r) { return trimSlash(r.baseUrl()) + "/api/v4"; }

    private JsonNode getJson(Remote r, String path) throws IOException {
        var res = http.get(api(r) + path, auth(r));
        if (res.status() / 100 != 2) throw new IOException("unexpected status " + res.status());
        try {
            return json.readTree(res.body());
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
                out.add(mergeRequest(repoExternalId, m, text(m.path("author").path("name")), ""));
            }
            if (items.size() < PAGE_SIZE) break;
            if (page == MAX_PAGES) { truncated = true; break; }
        }
        return new Polled(out, truncated);
    }

    @Override public int check(Remote remote) throws IOException {
        return http.get(api(remote) + "/user", auth(remote)).status();
    }
}
