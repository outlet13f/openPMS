package com.openpms.auth;

import static com.openpms.db.Tables.API_TOKENS;
import static com.openpms.db.Tables.USERS;

import com.openpms.common.ApiException;
import com.openpms.common.SettingsRepo;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * 외부 연동 API 토큰: "opms_" + 32 random bytes. Only the SHA-256 is stored; the token is shown once when made. A token acts
 * as its owner with the owner's rights, read-only (GET) or read-write; administrators can switch tokens off for everyone
 * (setting api.tokens_enabled) and revoke anyone's.
 */
@Component
public class ApiTokens {
    public static final String PREFIX = "opms_";
    public static final List<String> SCOPES = List.of("read", "write");
    public static final List<Integer> LIFETIMES = List.of(30, 90, 365);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Token(UUID id, String name, String scope, OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime expiresAt) {}

    /** What a request's token stands for. */
    public record Bearer(UUID tokenId, UUID userId, String scope) {}

    private final DSLContext db;
    private final SettingsRepo settings;

    public ApiTokens(DSLContext db, SettingsRepo settings) {
        this.db = db;
        this.settings = settings;
    }

    public boolean enabled() {
        return settings.bool("api.tokens_enabled", true);
    }

    /** A new token for the person; the raw value is returned once and never kept. */
    public String issue(UUID userId, String name, String scope, Integer days) {
        if (!enabled()) throw ApiException.forbidden("관리자가 API 토큰을 꺼 두었습니다.");
        var n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 60) throw ApiException.field("name", "토큰 이름을 1~60자로 입력하세요.");
        if (!SCOPES.contains(scope)) throw ApiException.field("scope", "권한 범위를 고르세요.");
        if (days != null && !LIFETIMES.contains(days)) throw ApiException.field("expiresInDays", "만료 기간을 고르세요.");
        if (db.fetchCount(API_TOKENS, API_TOKENS.USER_ID.eq(userId), API_TOKENS.REVOKED_AT.isNull()) >= 20)
            throw ApiException.unprocessable("토큰은 한 사람이 20개까지 만들 수 있습니다.", "쓰지 않는 토큰을 폐기하세요.");
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.insertInto(API_TOKENS).set(API_TOKENS.USER_ID, userId).set(API_TOKENS.NAME, n).set(API_TOKENS.TOKEN_HASH, Tokens.hash(token))
                .set(API_TOKENS.SCOPE, scope).set(API_TOKENS.EXPIRES_AT, days == null ? null : OffsetDateTime.now().plusDays(days)).execute();
        return token;
    }

    public List<Token> list(UUID userId) {
        return db.selectFrom(API_TOKENS).where(API_TOKENS.USER_ID.eq(userId), API_TOKENS.REVOKED_AT.isNull()).orderBy(API_TOKENS.CREATED_AT.desc())
                .fetch(r -> new Token(r.getId(), r.getName(), r.getScope(), r.getCreatedAt(), r.getLastUsedAt(), r.getExpiresAt()));
    }

    /** Revoke one token; userId null = any owner (administrators). */
    public void revoke(UUID id, UUID userId) {
        var cond = API_TOKENS.ID.eq(id).and(API_TOKENS.REVOKED_AT.isNull());
        if (userId != null) cond = cond.and(API_TOKENS.USER_ID.eq(userId));
        if (db.update(API_TOKENS).set(API_TOKENS.REVOKED_AT, OffsetDateTime.now()).where(cond).execute() == 0) throw ApiException.notFound("토큰을 찾을 수 없습니다.");
    }

    /** Every person's tokens still in force, for the administrators' overview: [id, owner id, owner name, name, scope, created, last used, expires]. */
    public List<org.jooq.Record> all() {
        return db.select(API_TOKENS.ID, USERS.ID, USERS.NAME, API_TOKENS.NAME, API_TOKENS.SCOPE, API_TOKENS.CREATED_AT, API_TOKENS.LAST_USED_AT, API_TOKENS.EXPIRES_AT)
                .from(API_TOKENS).join(USERS).on(USERS.ID.eq(API_TOKENS.USER_ID)).where(API_TOKENS.REVOKED_AT.isNull()).orderBy(API_TOKENS.CREATED_AT.desc())
                .fetch().map(r -> (org.jooq.Record) r);
    }

    /**
     * The token in force behind a raw value, its owner active and not locked out; null otherwise. Last use is written at most
     * once a minute.
     */
    public Bearer resolve(String raw) {
        if (raw == null || !raw.startsWith(PREFIX)) return null;
        var now = OffsetDateTime.now();
        var r = db.select(API_TOKENS.ID, API_TOKENS.USER_ID, API_TOKENS.SCOPE, API_TOKENS.EXPIRES_AT, API_TOKENS.LAST_USED_AT, USERS.STATUS, USERS.LOCKED_UNTIL)
                .from(API_TOKENS).join(USERS).on(USERS.ID.eq(API_TOKENS.USER_ID))
                .where(API_TOKENS.TOKEN_HASH.eq(Tokens.hash(raw)), API_TOKENS.REVOKED_AT.isNull()).fetchOne();
        if (r == null) return null;
        if (r.get(API_TOKENS.EXPIRES_AT) != null && r.get(API_TOKENS.EXPIRES_AT).isBefore(now)) return null;
        if (!"active".equals(r.get(USERS.STATUS)) || (r.get(USERS.LOCKED_UNTIL) != null && r.get(USERS.LOCKED_UNTIL).isAfter(now))) return null;
        var last = r.get(API_TOKENS.LAST_USED_AT);
        if (last == null || last.isBefore(now.minusMinutes(1)))
            db.update(API_TOKENS).set(API_TOKENS.LAST_USED_AT, now).where(API_TOKENS.ID.eq(r.get(API_TOKENS.ID))).execute();
        return new Bearer(r.get(API_TOKENS.ID), r.get(API_TOKENS.USER_ID), r.get(API_TOKENS.SCOPE));
    }
}
