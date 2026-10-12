package com.openpms.auth;

import static com.openpms.db.Tables.TEMPORARY_PASSWORDS;
import static com.openpms.db.Tables.USERS;

import com.openpms.api.Dto;
import com.openpms.common.SettingsRepo;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 임시 비밀번호 (docs/superpowers/specs/2026-10-12-temporary-password-design.md): an administrator gives someone a password
 * without mail. While a row is here the account's password is temporary: signing in with it only earns a change token
 * (AuthController.login), and the administrator may look it up again to pass it on. Every path that sets a password clears it.
 */
@Component
public class TemporaryPasswords {
    private static final Logger log = LoggerFactory.getLogger(TemporaryPasswords.class);
    // no 0 O 1 l I: retyped from a chat message without mistakes; specials that survive messengers
    static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String DIGITS = "23456789";
    static final String SPECIAL = "!@#$%*-_+=?";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Issued(String temporaryPassword, OffsetDateTime expiresAt) {}

    public record Shown(String temporaryPassword, OffsetDateTime expiresAt, Dto.IdName issuedBy, OffsetDateTime issuedAt) {}

    private final DSLContext db;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final SettingsRepo settings;
    private final Tokens tokens;
    private final Sessions sessions;

    public TemporaryPasswords(DSLContext db, PasswordEncoder encoder, PasswordPolicy policy, SettingsRepo settings, Tokens tokens, Sessions sessions) {
        this.db = db;
        this.encoder = encoder;
        this.policy = policy;
        this.settings = settings;
        this.tokens = tokens;
        this.sessions = sessions;
    }

    /** At least 16 characters (or the policy's minimum), one of each kind, shuffled: passes any password policy. */
    static String generate(int minLength) {
        var all = LOWER + UPPER + DIGITS + SPECIAL;
        var chars = new ArrayList<Character>();
        for (var set : List.of(LOWER, UPPER, DIGITS, SPECIAL)) chars.add(set.charAt(RANDOM.nextInt(set.length())));
        while (chars.size() < Math.max(16, minLength)) chars.add(all.charAt(RANDOM.nextInt(all.length())));
        Collections.shuffle(chars, RANDOM);
        var sb = new StringBuilder(chars.size());
        chars.forEach(sb::append);
        return sb.toString();
    }

    /** A new temporary password replaces the person's password (the caller has checked who may). Signs them out everywhere. */
    public Issued issue(UUID userId, UUID issuedBy) {
        var password = generate(policy.current().minLength());
        policy.check("password", password);
        var now = OffsetDateTime.now();
        var expiresAt = now.plusHours(settings.integer("auth.invitation_ttl_hours", 168));
        db.update(USERS).set(USERS.PASSWORD_HASH, encoder.encode(password)).set(USERS.FAILED_LOGIN_COUNT, 0).setNull(USERS.LOCKED_UNTIL)
                .where(USERS.ID.eq(userId)).execute();
        db.insertInto(TEMPORARY_PASSWORDS).set(TEMPORARY_PASSWORDS.USER_ID, userId).set(TEMPORARY_PASSWORDS.PASSWORD, password)
                .set(TEMPORARY_PASSWORDS.EXPIRES_AT, expiresAt).set(TEMPORARY_PASSWORDS.ISSUED_BY, issuedBy).set(TEMPORARY_PASSWORDS.ISSUED_AT, now)
                .onConflict(TEMPORARY_PASSWORDS.USER_ID).doUpdate()
                .set(TEMPORARY_PASSWORDS.PASSWORD, password).set(TEMPORARY_PASSWORDS.EXPIRES_AT, expiresAt)
                .set(TEMPORARY_PASSWORDS.ISSUED_BY, issuedBy).set(TEMPORARY_PASSWORDS.ISSUED_AT, now)
                .execute();
        tokens.revokeAll(userId, "password_reset");
        tokens.revokeAll(userId, "password_change");
        sessions.endAll(userId);
        log.info("temporary password issued for user {} by {}", userId, issuedBy);
        return new Issued(password, expiresAt);
    }

    /** The one in force, for the administrator to pass on; empty when there is none or it ran out. */
    public Optional<Shown> shown(UUID userId, UUID viewer) {
        var r = db.selectFrom(TEMPORARY_PASSWORDS)
                .where(TEMPORARY_PASSWORDS.USER_ID.eq(userId), TEMPORARY_PASSWORDS.EXPIRES_AT.gt(OffsetDateTime.now())).fetchOne();
        if (r == null) return Optional.empty();
        var by = r.getIssuedBy() == null ? null
                : db.select(USERS.ID, USERS.NAME).from(USERS).where(USERS.ID.eq(r.getIssuedBy())).fetchOne(x -> new Dto.IdName(x.value1(), x.value2()));
        log.info("temporary password of user {} looked up by {}", userId, viewer);
        return Optional.of(new Shown(r.getPassword(), r.getExpiresAt(), by, r.getIssuedAt()));
    }

    /** When the temporary password runs out, or null when the password is not temporary. */
    public OffsetDateTime expiresAt(UUID userId) {
        return db.select(TEMPORARY_PASSWORDS.EXPIRES_AT).from(TEMPORARY_PASSWORDS).where(TEMPORARY_PASSWORDS.USER_ID.eq(userId))
                .fetchOne(TEMPORARY_PASSWORDS.EXPIRES_AT);
    }

    /** Until when each one runs, run-out ones too until token-cleanup takes them (관리 · 사용자 shows it; never the password). */
    public Map<UUID, OffsetDateTime> untilByUser() {
        return db.select(TEMPORARY_PASSWORDS.USER_ID, TEMPORARY_PASSWORDS.EXPIRES_AT).from(TEMPORARY_PASSWORDS)
                .fetchMap(TEMPORARY_PASSWORDS.USER_ID, TEMPORARY_PASSWORDS.EXPIRES_AT);
    }

    /** The person set a password of their own (any path), or the account went: the temporary one is gone. */
    public void clear(UUID userId) {
        db.deleteFrom(TEMPORARY_PASSWORDS).where(TEMPORARY_PASSWORDS.USER_ID.eq(userId)).execute();
    }

    /**
     * Ran out: the row and the password itself go, or the old temporary password would work as an ordinary one.
     * Only a row that has really run out (a fresh one issued meanwhile stays). @return whether one went
     */
    public boolean expire(UUID userId, OffsetDateTime now) {
        int n = db.deleteFrom(TEMPORARY_PASSWORDS).where(TEMPORARY_PASSWORDS.USER_ID.eq(userId), TEMPORARY_PASSWORDS.EXPIRES_AT.le(now)).execute();
        if (n > 0) db.update(USERS).setNull(USERS.PASSWORD_HASH).where(USERS.ID.eq(userId)).execute();
        return n > 0;
    }

    /** token-cleanup: every run-out one. @return how many went */
    public int expireAll(OffsetDateTime now) {
        var ids = db.select(TEMPORARY_PASSWORDS.USER_ID).from(TEMPORARY_PASSWORDS).where(TEMPORARY_PASSWORDS.EXPIRES_AT.le(now))
                .fetch(TEMPORARY_PASSWORDS.USER_ID);
        return (int) ids.stream().filter(id -> expire(id, now)).count();
    }
}
