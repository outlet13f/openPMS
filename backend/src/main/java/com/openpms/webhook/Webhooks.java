package com.openpms.webhook;

import static com.openpms.db.Tables.WEBHOOKS;
import static com.openpms.db.Tables.WEBHOOK_DELIVERIES;

import com.openpms.common.Mailer;
import com.openpms.core.Ctx;
import com.openpms.core.Refs;
import com.openpms.db.tables.records.JournalsRecord;
import com.openpms.db.tables.records.WebhookDeliveriesRecord;
import com.openpms.db.tables.records.WebhooksRecord;
import com.openpms.db.tables.records.WorkPackagesRecord;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 웹훅: work package events go to other systems' addresses. An event becomes one delivery row per matching webhook; the
 * delivery job POSTs them (JSON, signed "X-OpenPMS-Signature: sha256=<HMAC-SHA256 of the body>"), no redirects followed,
 * 5 s to connect and 10 s for the answer; a non-2xx answer is tried again after 1, 5, 30 and 120 minutes, then marked failed.
 * Internal comments are never sent.
 */
@Service
public class Webhooks {
    private static final Logger log = LoggerFactory.getLogger(Webhooks.class);
    public static final List<String> EVENTS = List.of("work_package.created", "work_package.updated", "work_package.deleted", "work_package.commented");
    private static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofMinutes(120));
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DSLContext db;
    private final JsonMapper json;
    private final Mailer mailer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public Webhooks(DSLContext db, JsonMapper json, Mailer mailer) {
        this.db = db;
        this.json = json;
        this.mailer = mailer;
    }

    public static String newSecret() {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The work package as a payload shows it. */
    private Map<String, Object> describe(Ctx ctx, WorkPackagesRecord w) {
        var s = ctx.s();
        var project = s.project(w.getProjectId()).orElse(null);
        var m = new LinkedHashMap<String, Object>();
        m.put("id", w.getId());
        m.put("number", w.getNumber());
        m.put("subject", w.getSubject());
        m.put("url", project == null ? null : mailer.link("/projects/" + project.identifier() + "/work-packages/" + w.getNumber() + "/activity"));
        m.put("project", project == null ? null : Map.of("id", project.id(), "identifier", project.identifier(), "name", project.name()));
        m.put("type", Refs.type(s, w.getTypeId()));
        m.put("status", s.status(w.getStatusId()).map(Refs::status).orElse(null));
        m.put("priority", Refs.priority(s, w.getPriorityId()));
        m.put("assignee", Refs.user(s, w.getAssigneeId()));
        m.put("startDate", w.getStartDate());
        m.put("dueDate", w.getDueDate());
        m.put("doneRatio", w.getDoneRatio());
        return m;
    }

    /**
     * Queue an event for every enabled webhook that wants it and covers the item's project. extra: "changes" (labels with
     * from/to texts) for an update, "comment" for a comment.
     */
    public void enqueue(Ctx ctx, WorkPackagesRecord w, String event, Map<String, Object> extra) {
        var hooks = db.selectFrom(WEBHOOKS).where(WEBHOOKS.ENABLED.isTrue()).fetch().stream()
                .filter(h -> Arrays.asList(h.getEvents()).contains(event))
                .filter(h -> h.getProjectIds() == null || h.getProjectIds().length == 0 || Arrays.asList(h.getProjectIds()).contains(w.getProjectId()))
                .toList();
        if (hooks.isEmpty()) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("event", event);
        payload.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC));
        payload.put("actor", Refs.user(ctx.s(), ctx.userId()));
        payload.put("workPackage", describe(ctx, w));
        if (extra != null) payload.putAll(extra);
        var body = JSONB.valueOf(json.writeValueAsString(payload));
        for (var h : hooks)
            db.insertInto(WEBHOOK_DELIVERIES).set(WEBHOOK_DELIVERIES.WEBHOOK_ID, h.getId()).set(WEBHOOK_DELIVERIES.EVENT, event).set(WEBHOOK_DELIVERIES.PAYLOAD, body).execute();
    }

    /** An update's changes as the activity tab words them. */
    public static List<Map<String, Object>> changes(com.openpms.api.Dto.Journal j) {
        return j.details().stream().filter(d -> !d.field().startsWith("_"))
                .map(d -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("field", d.field());
                    m.put("label", d.label());
                    m.put("from", d.from());
                    m.put("to", d.to());
                    return (Map<String, Object>) m;
                }).toList();
    }

    public static boolean sendable(JournalsRecord j) {
        return !j.getIsInternal();
    }

    static String sign(String secret, String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** One attempt; the delivery row records the answer and, when it failed, when to try again. */
    public WebhookDeliveriesRecord attempt(WebhookDeliveriesRecord d) {
        var hook = db.selectFrom(WEBHOOKS).where(WEBHOOKS.ID.eq(d.getWebhookId())).fetchOne();
        if (hook == null) return d;
        var body = d.getPayload().data();
        Integer code = null;
        String error = null;
        try {
            var req = HttpRequest.newBuilder(URI.create(hook.getUrl())).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("User-Agent", "openPMS-Webhook/1")
                    .header("X-OpenPMS-Event", d.getEvent()).header("X-OpenPMS-Delivery", d.getId().toString())
                    .header("X-OpenPMS-Signature", sign(hook.getSecret(), body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (error.length() > 300) error = error.substring(0, 300);
        }
        int attempts = d.getAttempts() + 1;
        d.setAttempts(attempts);
        d.setResponseStatus(code);
        d.setError(error);
        if (code != null && code >= 200 && code < 300) {
            d.setStatus("succeeded");
            d.setDeliveredAt(OffsetDateTime.now());
            d.setError(null);
        } else if (attempts > BACKOFF.size()) {
            d.setStatus("failed");
            if (error == null) d.setError("HTTP " + code);
        } else {
            d.setStatus("pending");
            d.setNextAttemptAt(OffsetDateTime.now().plus(BACKOFF.get(attempts - 1)));
            if (error == null) d.setError("HTTP " + code);
        }
        d.store();
        return d;
    }

    /** The delivery job: what is due, oldest first, up to 50 at a time. */
    public int deliver(Instant now) {
        var due = db.selectFrom(WEBHOOK_DELIVERIES).where(WEBHOOK_DELIVERIES.STATUS.eq("pending"), WEBHOOK_DELIVERIES.NEXT_ATTEMPT_AT.le(now.atOffset(ZoneOffset.UTC)))
                .orderBy(WEBHOOK_DELIVERIES.NEXT_ATTEMPT_AT).limit(50).fetch();
        int ok = 0;
        for (var d : due) {
            if ("succeeded".equals(attempt(d).getStatus())) ok++;
            else log.info("webhook delivery {} to {} not accepted: {}", d.getId(), d.getWebhookId(), d.getError());
        }
        return due.size();
    }

    /** 테스트 전송: a "ping" sent right away, recorded like any delivery. */
    public WebhookDeliveriesRecord ping(Ctx ctx, WebhooksRecord hook) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("event", "ping");
        payload.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC));
        payload.put("actor", Refs.user(ctx.s(), ctx.userId()));
        payload.put("webhook", Map.of("id", hook.getId(), "name", hook.getName()));
        var d = db.insertInto(WEBHOOK_DELIVERIES).set(WEBHOOK_DELIVERIES.WEBHOOK_ID, hook.getId()).set(WEBHOOK_DELIVERIES.EVENT, "ping")
                .set(WEBHOOK_DELIVERIES.PAYLOAD, JSONB.valueOf(json.writeValueAsString(payload))).returning().fetchOne();
        return attemptOnce(d);
    }

    /** Send again now (a failed or pending delivery). */
    public WebhookDeliveriesRecord redeliver(UUID id) {
        var d = db.selectFrom(WEBHOOK_DELIVERIES).where(WEBHOOK_DELIVERIES.ID.eq(id)).fetchOne();
        if (d == null) throw com.openpms.common.ApiException.notFound("전송 기록을 찾을 수 없습니다.");
        d.setStatus("pending");
        d.setAttempts(0);
        return attemptOnce(d);
    }

    /** A manual send gets one try; a failure stays failed instead of waiting for retries. */
    private WebhookDeliveriesRecord attemptOnce(WebhookDeliveriesRecord d) {
        var r = attempt(d);
        if ("pending".equals(r.getStatus())) {
            r.setStatus("failed");
            r.store();
        }
        return r;
    }
}
