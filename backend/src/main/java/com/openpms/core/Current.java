package com.openpms.core;

import com.openpms.common.ApiException;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** The request's {@link Ctx}: the signed-in user (from the session) and a fresh snapshot. */
@Component
public class Current {
    private static final String KEY = "openpms.ctx";
    private final DSLContext db;
    private final JsonMapper json;

    public Current(DSLContext db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    public Snapshot snapshot() {
        return new Snapshot(db, json);
    }

    public static UUID signedInUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Ctx ctx() {
        var attrs = RequestContextHolder.currentRequestAttributes();
        var ctx = (Ctx) attrs.getAttribute(KEY, RequestAttributes.SCOPE_REQUEST);
        if (ctx != null) return ctx;
        var id = signedInUserId();
        if (id == null) throw new ApiException(401, "로그인이 필요합니다.");
        ctx = new Ctx(id, this::snapshot);
        // a session outlives a lock or a deletion; such users are signed out right away
        var u = ctx.s().user(id).orElse(null);
        if (u == null || !"active".equals(u.status())) throw new ApiException(401, "로그인이 필요합니다.");
        attrs.setAttribute(KEY, ctx, RequestAttributes.SCOPE_REQUEST);
        return ctx;
    }
}
