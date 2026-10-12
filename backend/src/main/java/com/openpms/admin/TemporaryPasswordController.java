package com.openpms.admin;

import static com.openpms.db.Tables.USERS;

import com.openpms.auth.ApiTokenFilter;
import com.openpms.auth.TemporaryPasswords;
import com.openpms.auth.Tokens;
import com.openpms.common.ApiException;
import com.openpms.core.Current;
import com.openpms.db.tables.records.UsersRecord;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리 · 사용자의 임시 비밀번호 발급·보기. Same rights as the rest of 관리 · 사용자; an administrator's account only by administrators. */
@RestController
@RequestMapping("/api/v1/admin/users/{id}/temporary-password")
public class TemporaryPasswordController {
    private final Current current;
    private final DSLContext db;
    private final TemporaryPasswords temporaryPasswords;
    private final Tokens tokens;

    public TemporaryPasswordController(Current current, DSLContext db, TemporaryPasswords temporaryPasswords, Tokens tokens) {
        this.current = current;
        this.db = db;
        this.temporaryPasswords = temporaryPasswords;
        this.tokens = tokens;
    }

    /**
     * The account whose password may be handled here, or why not. An API token never gets here: ApiTokenFilter closes the path, but
     * a percent-encoded letter in it slips past that string match, so the answer is refused here too.
     */
    private UsersRecord target(UUID id, HttpServletRequest req) {
        if (ApiTokenFilter.isBearer(req)) throw ApiException.forbidden("API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다.");
        var ctx = current.ctx();
        if (!ctx.isAdmin() && !ctx.globalPermissions().contains("global.manage_users")) throw ApiException.forbidden("사용자를 관리할 권한이 없습니다.");
        var u = db.selectFrom(USERS).where(USERS.ID.eq(id), USERS.STATUS.ne("deleted")).fetchOne();
        if (u == null) throw ApiException.notFound("사용자을(를) 찾을 수 없습니다.");
        if (u.getIsAdmin() && !ctx.isAdmin()) throw ApiException.forbidden("관리자 계정은 관리자만 다룰 수 있습니다.");
        if (u.getId().equals(ctx.userId())) throw ApiException.unprocessable("내 비밀번호는 계정 설정에서 바꾸세요.", null);
        if ("locked".equals(u.getStatus())) throw ApiException.unprocessable("잠긴 계정입니다. 먼저 잠금을 푸세요.", null);
        return u;
    }

    /** A new temporary password; someone still invited becomes active and their invitation link stops working. */
    @PostMapping
    @Transactional
    public ResponseEntity<TemporaryPasswords.Issued> issue(@PathVariable UUID id, HttpServletRequest req) {
        var u = target(id, req);
        var ctx = current.ctx();
        if ("invited".equals(u.getStatus())) {
            db.update(USERS).set(USERS.STATUS, "active").where(USERS.ID.eq(id)).execute();
            tokens.revokeAll(id, "invitation");
        }
        var issued = temporaryPasswords.issue(id, ctx.userId());
        ctx.refresh();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(issued);
    }

    @GetMapping
    public ResponseEntity<TemporaryPasswords.Shown> show(@PathVariable UUID id, HttpServletRequest req) {
        target(id, req);
        var shown = temporaryPasswords.shown(id, current.ctx().userId()).orElseThrow(() -> ApiException.notFound("유효한 임시 비밀번호가 없습니다."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shown);
    }
}
