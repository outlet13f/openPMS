package com.openpms.common;

import static com.openpms.db.Tables.IDEMPOTENCY_KEYS;

import com.openpms.core.Current;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A POST to /api/ with an "Idempotency-Key" header runs once per person and key: a retry (a network hiccup, a client that
 * resends) gets the first answer again, marked "Idempotent-Replayed: true", instead of making a second work package. Only
 * successful answers are kept, for a day (Housekeeping removes them); a refused or failed request may be sent again. The
 * same key with a different request is refused (422), and a retry while the first is still running gets 409. Runs after
 * Spring Security (an ordinary filter bean), so it knows who is asking; multipart uploads and answers over 1 MB are not
 * covered.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {
    public static final String HEADER = "Idempotency-Key";
    public static final Duration KEEP = Duration.ofHours(24);
    static final int MAX_BODY = 1024 * 1024;

    private final DSLContext db;
    private final JsonMapper json;

    public IdempotencyFilter(DSLContext db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    /** Answers that carry a temporary password are never kept (they would sit in idempotency_keys for a day). */
    private static final Pattern NOT_KEPT = Pattern.compile("^/api/v1/admin/users(/[^/]+/temporary-password)?$");
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        var type = req.getContentType();
        return !"POST".equals(req.getMethod()) || !req.getRequestURI().startsWith("/api/") || req.getHeader(HEADER) == null
                || (type != null && type.toLowerCase().startsWith("multipart/")) || NOT_KEPT.matcher(PATHS.getPathWithinApplication(req)).matches(); // decoded: %74 is a t as well
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var user = Current.signedInUserId();
        if (user == null) {
            chain.doFilter(req, res);
            return;
        }
        var key = req.getHeader(HEADER).trim();
        if (key.isEmpty() || key.length() > 255) {
            problem(res, 422, "Idempotency-Key는 1~255자여야 합니다.");
            return;
        }
        var body = req.getInputStream().readAllBytes();
        var path = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        var hash = sha256(req.getMethod() + " " + path + "\n", body);

        db.deleteFrom(IDEMPOTENCY_KEYS).where(IDEMPOTENCY_KEYS.USER_ID.eq(user), IDEMPOTENCY_KEYS.KEY.eq(key),
                IDEMPOTENCY_KEYS.CREATED_AT.lt(OffsetDateTime.now().minus(KEEP))).execute();
        int reserved = db.insertInto(IDEMPOTENCY_KEYS).set(IDEMPOTENCY_KEYS.USER_ID, user).set(IDEMPOTENCY_KEYS.KEY, key)
                .set(IDEMPOTENCY_KEYS.METHOD, req.getMethod()).set(IDEMPOTENCY_KEYS.PATH, path).set(IDEMPOTENCY_KEYS.REQUEST_HASH, hash)
                .onConflictDoNothing().execute();
        if (reserved == 0) {
            var seen = db.selectFrom(IDEMPOTENCY_KEYS).where(IDEMPOTENCY_KEYS.USER_ID.eq(user), IDEMPOTENCY_KEYS.KEY.eq(key)).fetchOne();
            if (seen == null) { // removed in between: run it, without a record
                chain.doFilter(new CachedBody(req, body), res);
                return;
            }
            if (!seen.getRequestHash().equals(hash)) problem(res, 422, "같은 Idempotency-Key를 다른 요청에 썼습니다. 요청마다 새 키를 쓰세요.");
            else if (seen.getStatus() == null) problem(res, 409, "같은 요청을 아직 처리하고 있습니다. 잠시 뒤 다시 시도하세요.");
            else {
                res.setStatus(seen.getStatus());
                if (seen.getContentType() != null) res.setContentType(seen.getContentType());
                res.setHeader("Idempotent-Replayed", "true");
                if (seen.getBody() != null) res.getOutputStream().write(seen.getBody());
            }
            return;
        }

        var wrapped = new ContentCachingResponseWrapper(res);
        var done = false;
        try {
            chain.doFilter(new CachedBody(req, body), wrapped);
            var status = wrapped.getStatus();
            var out = wrapped.getContentAsByteArray();
            if (status >= 200 && status < 300 && out.length <= MAX_BODY) {
                db.update(IDEMPOTENCY_KEYS).set(IDEMPOTENCY_KEYS.STATUS, status).set(IDEMPOTENCY_KEYS.CONTENT_TYPE, wrapped.getContentType())
                        .set(IDEMPOTENCY_KEYS.BODY, out).where(IDEMPOTENCY_KEYS.USER_ID.eq(user), IDEMPOTENCY_KEYS.KEY.eq(key)).execute();
                done = true;
            }
        } finally {
            if (!done) db.deleteFrom(IDEMPOTENCY_KEYS).where(IDEMPOTENCY_KEYS.USER_ID.eq(user), IDEMPOTENCY_KEYS.KEY.eq(key)).execute();
            wrapped.copyBodyToResponse();
        }
    }

    private void problem(HttpServletResponse res, int status, String title) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json;charset=UTF-8");
        res.getWriter().write(json.writeValueAsString(ProblemAdvice.body(status, title, null, null, null)));
    }

    private static String sha256(String head, byte[] body) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(head.getBytes(StandardCharsets.UTF_8));
            md.update(body);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The request again, its body already read once here. */
    private static final class CachedBody extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBody(HttpServletRequest req, byte[] body) {
            super(req);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            var cs = getCharacterEncoding() == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }
}
