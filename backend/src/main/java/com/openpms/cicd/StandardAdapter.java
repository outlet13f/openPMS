package com.openpms.cicd;

import static com.openpms.cicd.AdapterSupport.instant;
import static com.openpms.cicd.AdapterSupport.same;
import static com.openpms.cicd.AdapterSupport.time;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The "standard notice" Jenkins and Argo CD send: one JSON object per pipeline or deployment state. It comes from outside
 * with only the connection's secret to vouch for it, so every field is checked (type, length, allowed values) and a notice
 * that does not fit gives no event. Nothing from the payload goes into an exception or a log line.
 */
public abstract class StandardAdapter implements CicdAdapter {
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{7,64}");
    private static final int URL_MAX = 2000;

    private final JsonMapper json;

    protected StandardAdapter(JsonMapper json) {
        this.json = json;
    }

    /** Does this tool send build (pipeline) notices? */
    protected boolean acceptsPipelines() { return true; }

    @Override public boolean verify(Map<String, String> headers, byte[] body, String secret) {
        return signatureMatches(headers, body, secret);
    }

    /** x-openpms-signature = "sha256=" + hex HMAC-SHA256 of the body, compared in constant time. */
    protected static boolean signatureMatches(Map<String, String> headers, byte[] body, String secret) {
        var given = headers.get("x-openpms-signature");
        if (given == null || secret == null || secret.isEmpty() || body == null) return false;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return same(given, "sha256=" + HexFormat.of().formatHex(mac.doFinal(body)));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** A string value of at most max characters; null when missing, not a string, too long, or (unless blankOk) blank. */
    private static String str(JsonNode n, int max, boolean blankOk) {
        if (n == null || !n.isString()) return null;
        var s = n.stringValue();
        if (s.length() > max || (!blankOk && s.isBlank())) return null;
        return s;
    }

    /** "" when absent or null, the text when it fits, null when present but not usable. */
    private static String optional(JsonNode root, String field, int max) {
        var n = root.get(field);
        if (n == null || n.isNull()) return "";
        return str(n, max, true);
    }

    @Override public List<CicdEvent> parse(Map<String, String> headers, byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (root == null || !root.isObject()) return List.of();

        var type = str(root.get("type"), 20, false);
        var pipeline = "pipeline".equals(type);
        if (!pipeline && !"deployment".equals(type)) return List.of();
        if (pipeline && !acceptsPipelines()) return List.of();

        var repository = str(root.get("repository"), 300, false);
        var id = str(root.get("id"), 200, false);
        var status = str(root.get("status"), 20, false);
        var at = time(str(root.get("at"), 64, false));
        if (repository == null || id == null || status == null || at == null) return List.of();

        // Optional fields: absent or null is fine, present but too long or not text spoils the notice.
        var image = optional(root, "image", 500);
        var ref = optional(root, "ref", 255);
        var name = optional(root, "name", 200);
        if (image == null || ref == null || name == null) return List.of();
        var urlNode = root.get("url");
        var url = urlNode != null && urlNode.isString() && urlNode.stringValue().length() <= URL_MAX ? urlNode.stringValue() : "";

        var shaNode = root.get("sha");
        var shaText = shaNode != null && shaNode.isString() && shaNode.stringValue().length() <= 64
                ? shaNode.stringValue().toLowerCase(Locale.ROOT) : "";
        var sha = HEX.matcher(shaText).matches() ? shaText : null;

        var repo = repository.toLowerCase(Locale.ROOT);
        if (pipeline) {
            if (sha == null) return List.of();
            var state = switch (status) {
                case "running", "success", "failed", "canceled" -> status;
                default -> null;
            };
            if (state == null) return List.of();
            var running = state.equals("running");
            var p = new CicdEvent.Pipeline(id, name, sha, ref, state, url, running ? at : null, running ? null : at, at);
            return List.of(new CicdEvent("pipeline", "pipeline:std:" + repo + ":" + id + ":" + state + ":" + instant(at),
                    null, null, null, p, null, repository));
        }
        var environment = str(root.get("environment"), 100, false);
        if (environment == null) return List.of();
        var state = switch (status) {
            case "running", "success", "failed" -> status;
            default -> null;
        };
        if (state == null) return List.of();
        var d = new CicdEvent.Deployment(id, sha, image, environment, state, url, at);
        return List.of(new CicdEvent("deployment", "deployment:std:" + repo + ":" + id + ":" + state + ":" + instant(at),
                null, null, null, null, d, repository));
    }

    @Override public List<RemoteRepo> listRepositories(Remote remote, String query) { return List.of(); }

    @Override public RemoteRepo repository(Remote remote, String externalId) { return null; }

    @Override public Polled poll(Remote remote, String repoExternalId, OffsetDateTime since) { return new Polled(List.of(), false); }

    @Override public int check(Remote remote) { return 0; }
}
