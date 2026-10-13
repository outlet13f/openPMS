package com.openpms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpms.admin.AdminController;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * Requests and answers that carry a password or a one-time token print without it: Spring MVC logs the objects it reads
 * and writes at DEBUG (logging.level.web=debug), and a record's own toString would print every field.
 */
class SecretToStringTest {
    static final String SECRET = "S3cret-Value!";

    @Test
    void credentialRecordsDoNotPrintTheirSecrets() {
        var now = OffsetDateTime.now();
        for (Object o : new Object[] {
                new AuthController.LoginInput("a@openpms.local", SECRET),
                new AuthController.ResetConfirm(SECRET, SECRET),
                new AuthController.AcceptInput("이름", SECRET),
                new AuthController.PasswordChange(SECRET, SECRET),
                new TemporaryPasswords.Issued(SECRET, now),
                new TemporaryPasswords.Shown(SECRET, now, null, now),
                new AdminController.CreatedWithPassword(null, SECRET, now)}) {
            assertThat(o.toString()).as(o.getClass().getSimpleName()).doesNotContain(SECRET);
        }
        // what is not secret still helps a reader of the log
        assertThat(new AuthController.LoginInput("a@openpms.local", SECRET).toString()).contains("a@openpms.local");
    }
}
