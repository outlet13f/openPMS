package com.openpms.auth;

import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.USER_PREFERENCES;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.Mailer;
import com.openpms.common.SettingsRepo;
import com.openpms.core.Current;
import com.openpms.db.tables.records.UsersRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in, sign-out, the current user, password reset and invitation acceptance (architecture.md §4.2 인증·계정). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record LoginInput(String email, String password) {}

    public record ResetRequest(String email) {}

    public record ResetConfirm(String token, String newPassword) {}

    public record AcceptInput(String name, String password) {}

    public record Invitation(String email, String name, String invitedBy) {}

    private final DSLContext db;
    private final PasswordEncoder encoder;
    private final SecurityContextRepository contextRepo;
    private final RateLimiter limiter;
    private final Tokens tokens;
    private final Mailer mailer;
    private final SettingsRepo settings;
    private final Current current;
    private final Sessions sessions;
    private final int loginsPerMinute;

    public AuthController(DSLContext db, PasswordEncoder encoder, SecurityContextRepository contextRepo, RateLimiter limiter, Tokens tokens,
                          Mailer mailer, SettingsRepo settings, Current current, Sessions sessions,
                          @org.springframework.beans.factory.annotation.Value("${openpms.logins-per-minute:20}") int loginsPerMinute) {
        this.loginsPerMinute = loginsPerMinute;
        this.db = db;
        this.encoder = encoder;
        this.contextRepo = contextRepo;
        this.limiter = limiter;
        this.tokens = tokens;
        this.mailer = mailer;
        this.settings = settings;
        this.current = current;
        this.sessions = sessions;
    }

    private UsersRecord byEmail(String email) {
        return email == null ? null : db.selectFrom(USERS).where(USERS.EMAIL.eq(email.trim())).fetchOne();
    }

    /** Start a session for this user (new session id: no fixation). */
    private void signIn(UUID userId, HttpServletRequest req, HttpServletResponse res) {
        if (req.getSession(false) != null) req.changeSessionId();
        else req.getSession(true);
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, List.of()));
        SecurityContextHolder.setContext(ctx);
        contextRepo.saveContext(ctx, req, res);
    }

    @PostMapping("/login")
    @Transactional(noRollbackFor = ApiException.class)
    public Dto.Me login(@RequestBody LoginInput in, HttpServletRequest req, HttpServletResponse res) {
        limiter.check("login:" + req.getRemoteAddr(), loginsPerMinute);
        var u = byEmail(in.email());
        var now = OffsetDateTime.now();
        if (u != null && ("locked".equals(u.getStatus()) || (u.getLockedUntil() != null && u.getLockedUntil().isAfter(now)))) {
            throw new ApiException(423, "잠긴 계정입니다.", "locked".equals(u.getStatus()) ? "관리자에게 계정 잠금 해제를 요청하세요." : "잠시 뒤 다시 시도하세요.");
        }
        boolean ok = u != null && "active".equals(u.getStatus()) && u.getPasswordHash() != null && in.password() != null
                && encoder.matches(in.password(), u.getPasswordHash());
        if (!ok) {
            if (u != null) {
                int failures = u.getFailedLoginCount() + 1;
                var update = db.update(USERS).set(USERS.FAILED_LOGIN_COUNT, failures);
                if (failures >= settings.integer("auth.lockout_attempts", 5)) {
                    update = update.set(USERS.FAILED_LOGIN_COUNT, 0).set(USERS.LOCKED_UNTIL, now.plusMinutes(settings.integer("auth.lockout_minutes", 15)));
                }
                update.where(USERS.ID.eq(u.getId())).execute();
            }
            throw new ApiException(401, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        db.update(USERS).set(USERS.FAILED_LOGIN_COUNT, 0).setNull(USERS.LOCKED_UNTIL).set(USERS.LAST_LOGIN_AT, now).where(USERS.ID.eq(u.getId())).execute();
        signIn(u.getId(), req, res);
        return me();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        var s = req.getSession(false);
        if (s != null) s.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Dto.Me me() {
        return me(current.ctx());
    }

    /** Me as /auth/me shows it (also the answer of PATCH /me). */
    public Dto.Me me(com.openpms.core.Ctx ctx) {
        var u = ctx.user();
        var p = db.selectFrom(USER_PREFERENCES).where(USER_PREFERENCES.USER_ID.eq(u.id())).fetchOne();
        var prefs = com.openpms.notification.AccountController.preferences(p);
        return new Dto.Me(u.id(), u.name(), u.email(), u.status(), u.isAdmin(), u.locale(), u.timeZone(), ctx.globalPermissions(), prefs, u.theme());
    }

    /** Always 202, whether or not the address has an account (no enumeration). */
    @PostMapping("/password-reset")
    @Transactional
    public ResponseEntity<Void> requestReset(@RequestBody ResetRequest in, HttpServletRequest req) {
        limiter.check("reset:" + req.getRemoteAddr(), 5);
        var u = byEmail(in.email());
        if (u != null && "active".equals(u.getStatus())) {
            tokens.revokeAll(u.getId(), "password_reset"); // only the newest link works
            var token = tokens.issue(u.getId(), "password_reset", Duration.ofMinutes(settings.integer("auth.password_reset_ttl_minutes", 60)), null);
            mailer.send(u.getEmail(), "[openPMS] 비밀번호 재설정",
                    u.getName() + "님,\n\n아래 링크에서 새 비밀번호를 정하세요. 링크는 한 번만 쓸 수 있고 곧 만료됩니다.\n\n"
                            + mailer.link("/password-reset?token=" + token)
                            + "\n\n직접 요청하지 않았다면 이 메일을 무시하세요. 비밀번호는 바뀌지 않습니다.");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private void checkPassword(String password) {
        int min = settings.integer("auth.password_min_length", 10);
        if (password == null || password.length() < min) throw ApiException.field("password", "비밀번호는 " + min + "자 이상이어야 합니다.");
        if (password.length() > 128) throw ApiException.field("password", "비밀번호는 128자 이하로 정하세요.");
    }

    @PostMapping("/password-reset/confirm")
    @Transactional
    public ResponseEntity<Void> confirmReset(@RequestBody ResetConfirm in) {
        var t = tokens.valid(in.token(), "password_reset");
        checkPassword(in.newPassword());
        db.update(USERS).set(USERS.PASSWORD_HASH, encoder.encode(in.newPassword())).set(USERS.FAILED_LOGIN_COUNT, 0).setNull(USERS.LOCKED_UNTIL)
                .where(USERS.ID.eq(t.getUserId())).execute();
        tokens.revokeAll(t.getUserId(), "password_reset");
        sessions.endAll(t.getUserId()); // a reset signs the account out everywhere
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invitations/{token}")
    public Invitation invitation(@PathVariable String token) {
        var t = tokens.valid(token, "invitation");
        var u = db.selectFrom(USERS).where(USERS.ID.eq(t.getUserId())).fetchOne();
        if (u == null || !"invited".equals(u.getStatus())) throw new ApiException(410, "링크가 만료됐거나 이미 사용됐습니다.");
        var by = t.getCreatedBy() == null ? null : db.select(USERS.NAME).from(USERS).where(USERS.ID.eq(t.getCreatedBy())).fetchOne(USERS.NAME);
        return new Invitation(u.getEmail(), u.getName(), by);
    }

    @PostMapping("/invitations/{token}/accept")
    @Transactional
    public Dto.Me accept(@PathVariable String token, @RequestBody AcceptInput in, HttpServletRequest req, HttpServletResponse res) {
        var t = tokens.valid(token, "invitation");
        var name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty() || name.length() > 100) throw ApiException.field("name", "이름을 1~100자로 입력하세요.");
        checkPassword(in.password());
        int n = db.update(USERS).set(USERS.NAME, name).set(USERS.PASSWORD_HASH, encoder.encode(in.password())).set(USERS.STATUS, "active")
                .set(USERS.LAST_LOGIN_AT, OffsetDateTime.now())
                .where(USERS.ID.eq(t.getUserId()), USERS.STATUS.eq("invited")).execute();
        if (n == 0) throw new ApiException(410, "링크가 만료됐거나 이미 사용됐습니다.");
        db.insertInto(USER_PREFERENCES).set(USER_PREFERENCES.USER_ID, t.getUserId()).onConflictDoNothing().execute();
        tokens.revokeAll(t.getUserId(), "invitation");
        signIn(t.getUserId(), req, res);
        return me();
    }
}
