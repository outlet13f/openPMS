package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_CONNECTIONS;

import com.openpms.auth.RateLimiter;
import com.openpms.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint reachable without a session (SecurityConfig): a code host posts its signed webhook here. Whatever makes us
 * unable to accept it (unknown key, switched off, no secret, no encryption key, bad signature) gives the same 404, so the
 * address cannot be probed. Nothing is processed or fetched in the request: the events are only stored (CicdEvents).
 */
@RestController
public class CicdHookController {
    private static final Logger log = LoggerFactory.getLogger(CicdHookController.class);
    /** Verified against when the key is unknown, so a miss costs about what a hit does. */
    private static final String DUMMY_SECRET = "no-such-connection";

    private final DSLContext db;
    private final SecretBox box;
    private final Adapters adapters;
    private final CicdEvents events;
    private final RateLimiter limiter;
    private final int maxBytes;

    public CicdHookController(DSLContext db, SecretBox box, Adapters adapters, CicdEvents events, RateLimiter limiter,
                              @Value("${openpms.cicd.max-hook-bytes:1048576}") int maxBytes) {
        this.db = db;
        this.box = box;
        this.adapters = adapters;
        this.events = events;
        this.limiter = limiter;
        this.maxBytes = maxBytes;
    }

    @PostMapping("/api/v1/hooks/cicd/{hookKey}")
    public ResponseEntity<Map<String, Integer>> receive(@PathVariable String hookKey, HttpServletRequest req) throws IOException {
        limiter.check("cicd-hook:" + req.getRemoteAddr(), 600);
        if (req.getContentLengthLong() > maxBytes) throw new ApiException(413, "본문이 너무 큽니다.");
        byte[] body = req.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) throw new ApiException(413, "본문이 너무 큽니다.");
        var c = db.selectFrom(CICD_CONNECTIONS).where(CICD_CONNECTIONS.HOOK_KEY.eq(hookKey)).fetchOne();
        var headers = lowerCaseHeaders(req);
        boolean ok = false;
        if (c == null) {
            adapters.of("github").verify(headers, body, DUMMY_SECRET); // result ignored: only keeps the timing alike
        } else if (c.getActive() && c.getWebhookSecretEnc() != null && box.ready()) {
            ok = adapters.of(c.getKind()).verify(headers, body, box.open(c.getWebhookSecretEnc()));
        }
        if (!ok) throw ApiException.notFound("찾을 수 없습니다.");
        // signed but unreadable or an event kind we do not use: nothing to store, accepted: 0
        var parsed = adapters.of(c.getKind()).parse(headers, body);
        int n = events.store(c.getId(), parsed, "webhook");
        // delivery ids are for debugging only; never the headers, body, secret or key
        log.debug("cicd hook {} delivery={} events={} new={}", c.getId(),
                headers.getOrDefault("x-github-delivery", headers.get("x-gitlab-event-uuid")), parsed.size(), n);
        return ResponseEntity.accepted().body(Map.of("accepted", n));
    }

    private static Map<String, String> lowerCaseHeaders(HttpServletRequest req) {
        var out = new HashMap<String, String>();
        for (var name : Collections.list(req.getHeaderNames())) out.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), req.getHeader(name));
        return out;
    }
}
