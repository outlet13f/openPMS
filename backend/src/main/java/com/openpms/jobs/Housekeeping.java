package com.openpms.jobs;

import static com.openpms.db.Tables.ATTACHMENTS;
import static com.openpms.db.Tables.USER_TOKENS;

import com.openpms.attachment.FileStorage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** Cleanup jobs (architecture.md §4 token-cleanup, file-sweep). */
@Component
public class Housekeeping {
    private final DSLContext db;
    private final FileStorage storage;
    private final com.openpms.auth.TemporaryPasswords temporaryPasswords;

    public Housekeeping(DSLContext db, FileStorage storage, com.openpms.auth.TemporaryPasswords temporaryPasswords) {
        this.db = db;
        this.storage = storage;
        this.temporaryPasswords = temporaryPasswords;
    }

    /**
     * Invitation and reset tokens a week past expiry or use; API tokens 30 days after they were revoked or ran out; webhook
     * deliveries done (succeeded or failed) more than 30 days ago; Idempotency-Key answers after a day; run-out temporary passwords. @return rows deleted
     */
    public int tokens(Instant now) {
        var cutoff = OffsetDateTime.ofInstant(now.minus(Duration.ofDays(7)), ZoneOffset.UTC);
        var month = OffsetDateTime.ofInstant(now.minus(Duration.ofDays(30)), ZoneOffset.UTC);
        int n = db.deleteFrom(USER_TOKENS).where(USER_TOKENS.EXPIRES_AT.lt(cutoff).or(USER_TOKENS.USED_AT.lt(cutoff))).execute();
        n += db.deleteFrom(com.openpms.db.Tables.API_TOKENS)
                .where(com.openpms.db.Tables.API_TOKENS.REVOKED_AT.lt(month).or(com.openpms.db.Tables.API_TOKENS.EXPIRES_AT.lt(month))).execute();
        // answers kept for Idempotency-Key retries: a day
        n += db.deleteFrom(com.openpms.db.Tables.IDEMPOTENCY_KEYS)
                .where(com.openpms.db.Tables.IDEMPOTENCY_KEYS.CREATED_AT.lt(OffsetDateTime.ofInstant(now.minus(com.openpms.common.IdempotencyFilter.KEEP), ZoneOffset.UTC))).execute();
        n += db.deleteFrom(com.openpms.db.Tables.WEBHOOK_DELIVERIES)
                .where(com.openpms.db.Tables.WEBHOOK_DELIVERIES.STATUS.ne("pending"), com.openpms.db.Tables.WEBHOOK_DELIVERIES.CREATED_AT.lt(month)).execute();
        // 임시 비밀번호 that ran out: the row and the password itself (TemporaryPasswords.expire)
        n += temporaryPasswords.expireAll(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return n;
    }

    /** Files on the volume that no attachment row points to, older than a day. @return files deleted */
    public int files(Instant now) throws IOException {
        var keep = new HashSet<>(db.select(ATTACHMENTS.STORAGE_KEY).from(ATTACHMENTS).fetch(ATTACHMENTS.STORAGE_KEY));
        return storage.sweep(keep, now.minus(Duration.ofDays(1)));
    }
}
