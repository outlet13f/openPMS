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
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GitHub (Cloud and Enterprise Server): webhooks are signed (X-Hub-Signature-256); polling uses the REST API with a bearer token. */
@Component
public class GitHubAdapter implements CicdAdapter {
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
        if (event == null || !(event.equals("push") || event.equals("pull_request"))) return List.of();
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            return List.of();
        }
        var repo = text(root.path("repository").path("id"));
        if (repo.isEmpty()) return List.of();
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
                merged, updated, text(pr.path("html_url")));
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
        var res = http.get(api(r) + path, auth(r));
        if (res.status() / 100 != 2) throw new IOException("unexpected status " + res.status());
        try {
            return json.readTree(res.body());
        } catch (JacksonException e) {
            throw new IOException("unreadable response");
        }
    }

    @Override public RemoteRepo repository(Remote remote, String externalId) throws IOException {
        var res = http.get(api(remote) + "/repositories/" + enc(externalId), auth(remote));
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
        return new RemoteRepo(id, text(p.path("full_name")), text(p.path("html_url")));
    }

    @Override public List<RemoteRepo> listRepositories(Remote remote, String query) throws IOException {
        var q = query == null ? "" : query.strip().toLowerCase();
        var out = new ArrayList<RemoteRepo>();
        for (var p : getJson(remote, "/user/repos?per_page=100")) {
            var name = text(p.path("full_name"));
            if (!name.toLowerCase().contains(q)) continue;
            out.add(new RemoteRepo(text(p.path("id")), name, text(p.path("html_url"))));
            if (out.size() == 20) break;
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
        return new Polled(out, truncated);
    }

    @Override public int check(Remote remote) throws IOException {
        return http.get(api(remote) + "/user", auth(remote)).status();
    }
}
