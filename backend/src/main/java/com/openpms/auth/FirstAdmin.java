package com.openpms.auth;

import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.USER_PREFERENCES;

import com.openpms.common.Mailer;
import java.time.Duration;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * First administrator (architecture.md §1 배포 구성): with no active admin and OPENPMS_ADMIN_EMAIL set, startup
 * invites that address as an admin and logs a one-time setup link (24 hours). There is no default password.
 */
@Component
public class FirstAdmin implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FirstAdmin.class);
    private final DSLContext db;
    private final Tokens tokens;
    private final Mailer mailer;
    private final String email;

    public FirstAdmin(DSLContext db, Tokens tokens, Mailer mailer, @Value("${openpms.admin-email:}") String email) {
        this.db = db;
        this.tokens = tokens;
        this.mailer = mailer;
        this.email = email == null ? "" : email.trim().toLowerCase();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isEmpty() || db.fetchExists(USERS, USERS.IS_ADMIN.isTrue().and(USERS.STATUS.eq("active")))) return;
        var user = db.selectFrom(USERS).where(USERS.EMAIL.eq(email)).fetchOne();
        if (user == null) {
            user = db.insertInto(USERS).set(USERS.EMAIL, email).set(USERS.NAME, "관리자").set(USERS.STATUS, "invited").set(USERS.IS_ADMIN, true)
                    .returning().fetchOne();
            db.insertInto(USER_PREFERENCES).set(USER_PREFERENCES.USER_ID, user.getId()).execute();
        } else if (!"invited".equals(user.getStatus())) {
            log.warn("OPENPMS_ADMIN_EMAIL {} belongs to an account that is {}; not changing it", email, user.getStatus());
            return;
        } else {
            db.update(USERS).set(USERS.IS_ADMIN, true).where(USERS.ID.eq(user.getId())).execute();
        }
        tokens.revokeAll(user.getId(), "invitation"); // each start prints a fresh link; the older ones stop working
        var token = tokens.issue(user.getId(), "invitation", Duration.ofHours(24), null);
        // printed once, on purpose: this is how the first administrator gets in on a fresh server
        log.warn("No administrator yet. Set up {} within 24 hours: {}", email, mailer.link("/invitations/" + token));
    }
}
