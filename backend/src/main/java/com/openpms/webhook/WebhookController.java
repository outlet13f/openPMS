package com.openpms.webhook;

import static com.openpms.db.Tables.WEBHOOKS;
import static com.openpms.db.Tables.WEBHOOK_DELIVERIES;

import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.db.tables.records.WebhookDeliveriesRecord;
import com.openpms.db.tables.records.WebhooksRecord;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리 · API와 웹훅 · 웹훅 (administrators only): the webhooks, their recent deliveries, a test send, sending again. */
@RestController
@RequestMapping("/api/v1/admin")
public class WebhookController {
    /** secretHint: the last four characters; the whole secret only in the answer that made it. */
    public record Webhook(UUID id, String name, String url, List<String> events, List<UUID> projectIds, boolean enabled, String secretHint,
                          OffsetDateTime createdAt, String secret) {}

    public record Delivery(UUID id, String event, String status, int attempts, Integer responseStatus, String error, OffsetDateTime createdAt,
                           OffsetDateTime deliveredAt, OffsetDateTime nextAttemptAt) {}

    private final Current current;
    private final DSLContext db;
    private final Webhooks webhooks;

    public WebhookController(Current current, DSLContext db, Webhooks webhooks) {
        this.current = current;
        this.db = db;
        this.webhooks = webhooks;
    }

    private Ctx admin() {
        var ctx = current.ctx();
        if (!ctx.isAdmin()) throw ApiException.forbidden("관리자만 할 수 있습니다.");
        return ctx;
    }

    private static Webhook dto(WebhooksRecord h, String secret) {
        var s = h.getSecret();
        return new Webhook(h.getId(), h.getName(), h.getUrl(), Arrays.asList(h.getEvents()), h.getProjectIds() == null ? List.of() : Arrays.asList(h.getProjectIds()),
                h.getEnabled(), "…" + s.substring(Math.max(0, s.length() - 4)), h.getCreatedAt(), secret);
    }

    private static Delivery dto(WebhookDeliveriesRecord d) {
        return new Delivery(d.getId(), d.getEvent(), d.getStatus(), d.getAttempts(), d.getResponseStatus(), d.getError(), d.getCreatedAt(), d.getDeliveredAt(),
                "pending".equals(d.getStatus()) ? d.getNextAttemptAt() : null);
    }

    private WebhooksRecord find(UUID id) {
        var h = db.selectFrom(WEBHOOKS).where(WEBHOOKS.ID.eq(id)).fetchOne();
        if (h == null) throw ApiException.notFound("웹훅을 찾을 수 없습니다.");
        return h;
    }

    /** name, url (http/https), events (known ones, at least one), projectIds (empty: all), enabled */
    private void apply(Ctx ctx, WebhooksRecord h, Map<String, Object> in, boolean creating) {
        if (creating || In.has(in, "name")) {
            var name = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
            if (name.isEmpty() || name.length() > 100) throw ApiException.field("name", "이름을 1~100자로 입력하세요.");
            h.setName(name);
        }
        if (creating || In.has(in, "url")) {
            var url = In.str(in, "url") == null ? "" : In.str(in, "url").trim();
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw ApiException.field("url", "주소 형식이 아닙니다.");
            }
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || url.length() > 2000)
                throw ApiException.field("url", "http:// 또는 https:// 로 시작하는 주소를 입력하세요.");
            h.setUrl(url);
        }
        if (creating || In.has(in, "events")) {
            var events = In.strings(in, "events");
            if (events == null || events.isEmpty() || !Webhooks.EVENTS.containsAll(events)) throw ApiException.field("events", "보낼 이벤트를 하나 이상 고르세요.");
            h.setEvents(new LinkedHashSet<>(events).toArray(String[]::new));
        }
        if (creating || In.has(in, "projectIds")) {
            var ids = In.uuids(in, "projectIds", "projectIds");
            for (var id : ids) if (ctx.s().project(id).isEmpty()) throw ApiException.field("projectIds", "알 수 없는 프로젝트가 있습니다.");
            h.setProjectIds(ids.isEmpty() ? null : ids.toArray(UUID[]::new));
        }
        if (In.has(in, "enabled")) h.setEnabled(Boolean.TRUE.equals(In.bool(in, "enabled")));
        else if (creating) h.setEnabled(true);
    }

    @GetMapping("/webhooks")
    public List<Webhook> list() {
        admin();
        return db.selectFrom(WEBHOOKS).orderBy(WEBHOOKS.CREATED_AT).fetch().stream().map(h -> dto(h, null)).toList();
    }

    @PostMapping("/webhooks")
    @Transactional
    public ResponseEntity<Webhook> create(@RequestBody Map<String, Object> in) {
        var ctx = admin();
        var h = db.newRecord(WEBHOOKS);
        apply(ctx, h, in, true);
        var secret = Webhooks.newSecret();
        h.setSecret(secret);
        h.setCreatedBy(ctx.userId());
        h.store();
        h.refresh();
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(h, secret));
    }

    @PatchMapping("/webhooks/{id}")
    @Transactional
    public Webhook update(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = admin();
        var h = find(id);
        apply(ctx, h, in, false);
        h.setUpdatedAt(OffsetDateTime.now());
        h.store();
        return dto(h, null);
    }

    /** A new signing secret, shown once; the old one stops at once. */
    @PostMapping("/webhooks/{id}/secret")
    @Transactional
    public Webhook rotate(@PathVariable UUID id) {
        admin();
        var h = find(id);
        var secret = Webhooks.newSecret();
        h.setSecret(secret);
        h.store();
        return dto(h, secret);
    }

    @DeleteMapping("/webhooks/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        admin();
        find(id).delete();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/webhooks/{id}/deliveries")
    public List<Delivery> deliveries(@PathVariable UUID id) {
        admin();
        find(id);
        return db.selectFrom(WEBHOOK_DELIVERIES).where(WEBHOOK_DELIVERIES.WEBHOOK_ID.eq(id)).orderBy(WEBHOOK_DELIVERIES.CREATED_AT.desc()).limit(30)
                .fetch().stream().map(WebhookController::dto).toList();
    }

    @PostMapping("/webhooks/{id}/test")
    public Delivery test(@PathVariable UUID id) {
        var ctx = admin();
        return dto(webhooks.ping(ctx, find(id)));
    }

    @PostMapping("/webhook-deliveries/{id}/redeliver")
    public Delivery redeliver(@PathVariable UUID id) {
        admin();
        return dto(webhooks.redeliver(id));
    }
}
