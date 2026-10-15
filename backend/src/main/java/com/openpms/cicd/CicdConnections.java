package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_CONNECTIONS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;

import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import java.net.URI;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tool connections (GitLab, GitHub): who may manage them, validation, secrets at rest, and the audit trail. */
@Service
public class CicdConnections {
    private static final Logger AUDIT = LoggerFactory.getLogger("openpms.audit");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> KINDS = Set.of("gitlab", "github");

    /** Never holds a secret. */
    public record ConnectionDto(UUID id, String kind, String name, String baseUrl, String webhookUrl, String tokenHint, String secretHint,
                                boolean active, int repositoryCount, OffsetDateTime createdAt) {}

    /** The webhook secret is shown once, here. */
    public record CreatedConnection(ConnectionDto connection, String webhookSecret) {
        @Override public String toString() {
            return "CreatedConnection[connection=" + connection + ", webhookSecret=***]";
        }
    }

    public record Secret(String webhookSecret) {
        @Override public String toString() {
            return "Secret[webhookSecret=***]";
        }
    }

    public record CheckResult(boolean ok, int status) {}

    private final DSLContext db;
    private final SecretBox box;
    private final Adapters adapters;
    private final String publicBase;

    public CicdConnections(DSLContext db, SecretBox box, Adapters adapters, @Value("${openpms.base-url:}") String publicBase) {
        this.db = db;
        this.box = box;
        this.adapters = adapters;
        this.publicBase = publicBase == null ? "" : publicBase.replaceAll("/+$", "");
    }

    /** System administrators, or people holding the global permission. */
    public static void requireManager(Ctx ctx) {
        if (!ctx.isAdmin() && !ctx.globalPermissions().contains("global.manage_cicd"))
            throw ApiException.forbidden("CI/CD 연동을 관리할 권한이 없습니다.");
    }

    public List<ConnectionDto> list() {
        return db.select(CICD_CONNECTIONS.fields()).select(repoCount()).from(CICD_CONNECTIONS)
                .orderBy(CICD_CONNECTIONS.NAME).fetch(r -> dto(r.into(CICD_CONNECTIONS), r.get("repo_count", Integer.class)));
    }

    @Transactional
    public CreatedConnection create(Ctx ctx, Map<String, Object> in) {
        var kind = In.str(in, "kind");
        if (kind == null || !KINDS.contains(kind)) throw ApiException.field("kind", "지원하지 않는 도구입니다.");
        var name = name(In.str(in, "name"));
        var baseUrl = baseUrl(In.str(in, "baseUrl"));
        var token = In.str(in, "apiToken");
        box.requireReady();
        if (db.fetchExists(CICD_CONNECTIONS, CICD_CONNECTIONS.NAME.eq(name))) throw duplicate();
        var secret = random();
        var r = db.newRecord(CICD_CONNECTIONS);
        r.setKind(kind);
        r.setName(name);
        r.setBaseUrl(baseUrl);
        if (token != null && !token.isBlank()) {
            r.setApiTokenEnc(box.seal(token));
            r.setApiTokenHint(SecretBox.hint(token));
        }
        r.setWebhookSecretEnc(box.seal(secret));
        r.setWebhookSecretHint(SecretBox.hint(secret));
        r.setHookKey(random());
        r.setActive(true);
        r.setCreatedBy(ctx.userId());
        r.insert();
        AUDIT.info("cicd.connection.created by={} id={} name={}", ctx.userId(), r.getId(), name);
        return new CreatedConnection(dto(r, 0), secret);
    }

    @Transactional
    public ConnectionDto update(Ctx ctx, UUID id, Map<String, Object> in) {
        var r = find(id);
        if (In.has(in, "name")) {
            var name = name(In.str(in, "name"));
            if (!name.equals(r.getName()) && db.fetchExists(CICD_CONNECTIONS, CICD_CONNECTIONS.NAME.eq(name))) throw duplicate();
            r.setName(name);
        }
        boolean tokenCleared = false;
        var newToken = In.has(in, "apiToken") ? In.str(in, "apiToken") : null;
        if (In.has(in, "baseUrl")) {
            var url = baseUrl(In.str(in, "baseUrl"));
            if (!url.equals(r.getBaseUrl())) {
                // a stored token must never follow the connection to another address
                r.setBaseUrl(url);
                if (r.getApiTokenEnc() != null) tokenCleared = true;
                r.setApiTokenEnc(null);
                r.setApiTokenHint(null);
            }
        }
        if (In.has(in, "apiToken")) {
            var token = newToken;
            if (token == null || token.isEmpty()) {
                r.setApiTokenEnc(null);
                r.setApiTokenHint(null);
            } else {
                box.requireReady();
                r.setApiTokenEnc(box.seal(token));
                r.setApiTokenHint(SecretBox.hint(token));
            }
        }
        if (In.has(in, "active")) {
            if (!(in.get("active") instanceof Boolean b)) throw ApiException.field("active", "켜짐 여부는 참/거짓이어야 합니다.");
            r.setActive(b);
        }
        r.update();
        if (tokenCleared && r.getApiTokenEnc() != null) tokenCleared = false; // replaced in the same request
        AUDIT.info("cicd.connection.updated by={} id={} name={} tokenCleared={}", ctx.userId(), id, r.getName(), tokenCleared);
        return dto(r, count(id));
    }

    @Transactional
    public Secret rotateSecret(Ctx ctx, UUID id) {
        var r = find(id);
        box.requireReady();
        var secret = random();
        r.setWebhookSecretEnc(box.seal(secret));
        r.setWebhookSecretHint(SecretBox.hint(secret));
        r.update();
        AUDIT.info("cicd.connection.secret_rotated by={} id={} name={}", ctx.userId(), id, r.getName());
        return new Secret(secret);
    }

    /** Only ok and the HTTP status leave here: never the other side's body, headers or exception text. */
    public CheckResult check(UUID id) {
        var r = find(id);
        if (r.getApiTokenEnc() == null) return new CheckResult(false, 0);
        try {
            int status = adapters.of(r.getKind()).check(new CicdAdapter.Remote(r.getBaseUrl(), box.open(r.getApiTokenEnc())));
            return new CheckResult(status >= 200 && status < 300, status);
        } catch (Exception e) {
            return new CheckResult(false, 0);
        }
    }

    @Transactional
    public void delete(Ctx ctx, UUID id) {
        var r = find(id);
        if (count(id) > 0) throw new ApiException(409, "저장소가 붙어 있는 연동은 지울 수 없습니다. 먼저 저장소를 떼세요.");
        r.delete();
        AUDIT.info("cicd.connection.deleted by={} id={} name={}", ctx.userId(), id, r.getName());
    }

    private com.openpms.db.tables.records.CicdConnectionsRecord find(UUID id) {
        var r = db.selectFrom(CICD_CONNECTIONS).where(CICD_CONNECTIONS.ID.eq(id)).fetchOne();
        if (r == null) throw ApiException.notFound("연동을 찾을 수 없습니다.");
        return r;
    }

    private int count(UUID id) {
        return db.fetchCount(CICD_REPOSITORIES, CICD_REPOSITORIES.CONNECTION_ID.eq(id));
    }

    private static org.jooq.Field<Integer> repoCount() {
        return DSL.field(DSL.select(DSL.count()).from(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(CICD_CONNECTIONS.ID))).cast(Integer.class).as("repo_count");
    }

    private ConnectionDto dto(com.openpms.db.tables.records.CicdConnectionsRecord r, int repos) {
        return new ConnectionDto(r.getId(), r.getKind(), r.getName(), r.getBaseUrl(), publicBase + "/api/v1/hooks/cicd/" + r.getHookKey(),
                r.getApiTokenHint(), r.getWebhookSecretHint(), Boolean.TRUE.equals(r.getActive()), repos, r.getCreatedAt());
    }

    private static ApiException duplicate() {
        return new ApiException(409, "같은 이름의 연동이 있습니다.");
    }

    private static String name(String v) {
        var n = v == null ? "" : v.trim();
        if (n.isEmpty() || n.length() > 100) throw ApiException.field("name", "이름은 1자에서 100자 사이여야 합니다.");
        // the name goes into the audit log line
        if (n.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) throw ApiException.field("name", "이름에 쓸 수 없는 문자가 있습니다.");
        return n;
    }

    static String baseUrl(String v) {
        var bad = ApiException.field("baseUrl", "주소 형식이 올바르지 않습니다.");
        if (v == null) throw bad;
        var s = v.trim().replaceAll("/+$", "");
        try {
            var u = new URI(s);
            var scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) throw bad;
            if (u.getHost() == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) throw bad;
            if (s.contains("?") || s.contains("#") || s.contains("@")) throw bad;
        } catch (java.net.URISyntaxException e) {
            throw bad;
        }
        return s;
    }

    private static String random() {
        var b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
