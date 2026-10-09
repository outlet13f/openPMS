package com.openpms.auth;

import static com.openpms.db.Tables.USER_TOKENS;

import com.openpms.common.ApiException;
import com.openpms.db.tables.records.UserTokensRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** One-time links for invitations and password resets. The raw token is only in the mail; the DB keeps its SHA-256. */
@Component
public class Tokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DSLContext db;

    public Tokens(DSLContext db) {
        this.db = db;
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Issue a new token; earlier unused tokens with the same purpose stop working. */
    public String issue(UUID userId, String purpose, Duration ttl, UUID createdBy) {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var now = OffsetDateTime.now();
        db.update(USER_TOKENS).set(USER_TOKENS.USED_AT, now)
                .where(USER_TOKENS.USER_ID.eq(userId), USER_TOKENS.PURPOSE.eq(purpose), USER_TOKENS.USED_AT.isNull()).execute();
        db.insertInto(USER_TOKENS)
                .set(USER_TOKENS.USER_ID, userId).set(USER_TOKENS.PURPOSE, purpose).set(USER_TOKENS.TOKEN_HASH, hash(token))
                .set(USER_TOKENS.EXPIRES_AT, now.plus(ttl)).set(USER_TOKENS.CREATED_BY, createdBy)
                .execute();
        return token;
    }

    /** A usable token or 410 Gone (expired, used or unknown look the same). */
    public UserTokensRecord valid(String token, String purpose) {
        var r = token == null ? null : db.selectFrom(USER_TOKENS).where(USER_TOKENS.TOKEN_HASH.eq(hash(token)), USER_TOKENS.PURPOSE.eq(purpose)).fetchOne();
        if (r == null || r.getUsedAt() != null || r.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ApiException(410, "링크가 만료됐거나 이미 사용됐습니다.", "새 링크를 요청하세요.");
        }
        return r;
    }

    public void markUsed(UserTokensRecord r) {
        db.update(USER_TOKENS).set(USER_TOKENS.USED_AT, OffsetDateTime.now()).where(USER_TOKENS.ID.eq(r.getId())).execute();
    }
}
