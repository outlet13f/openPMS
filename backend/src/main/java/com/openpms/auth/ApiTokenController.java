package com.openpms.auth;

import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.common.SettingsRepo;
import com.openpms.core.Current;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 외부 연동 API: my tokens (계정 설정), everyone's tokens and the on/off switch (관리 · API와 웹훅), and the OpenAPI document. */
@RestController
@RequestMapping("/api/v1")
public class ApiTokenController {
    public record Created(ApiTokens.Token token, String secret) {}

    public record AdminToken(UUID id, UUID userId, String userName, String name, String scope, OffsetDateTime createdAt, OffsetDateTime lastUsedAt,
                             OffsetDateTime expiresAt) {}

    public record ApiSettings(boolean tokensEnabled) {}

    private final Current current;
    private final ApiTokens tokens;
    private final SettingsRepo settings;

    public ApiTokenController(Current current, ApiTokens tokens, SettingsRepo settings) {
        this.current = current;
        this.tokens = tokens;
        this.settings = settings;
    }

    @GetMapping("/me/api-tokens")
    public List<ApiTokens.Token> mine() {
        return tokens.list(current.ctx().userId());
    }

    /** The secret is in this answer only. */
    @PostMapping("/me/api-tokens")
    @Transactional
    public ResponseEntity<Created> issue(@RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var days = In.has(in, "expiresInDays") ? In.integer(in, "expiresInDays", "expiresInDays") : null;
        var secret = tokens.issue(ctx.userId(), In.str(in, "name"), In.str(in, "scope"), days);
        var made = tokens.list(ctx.userId()).get(0);
        return ResponseEntity.status(HttpStatus.CREATED).body(new Created(made, secret));
    }

    @DeleteMapping("/me/api-tokens/{id}")
    @Transactional
    public ResponseEntity<Void> revokeMine(@PathVariable UUID id) {
        tokens.revoke(id, current.ctx().userId());
        return ResponseEntity.noContent().build();
    }

    private void admin() {
        if (!current.ctx().isAdmin()) throw ApiException.forbidden("관리자만 할 수 있습니다.");
    }

    @GetMapping("/admin/api-tokens")
    public List<AdminToken> all() {
        admin();
        return tokens.all().stream().map(r -> new AdminToken(r.get(0, UUID.class), r.get(1, UUID.class), r.get(2, String.class), r.get(3, String.class),
                r.get(4, String.class), r.get(5, OffsetDateTime.class), r.get(6, OffsetDateTime.class), r.get(7, OffsetDateTime.class))).toList();
    }

    @DeleteMapping("/admin/api-tokens/{id}")
    @Transactional
    public ResponseEntity<Void> revokeAny(@PathVariable UUID id) {
        admin();
        tokens.revoke(id, null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/settings/api")
    public ApiSettings apiSettings() {
        admin();
        return new ApiSettings(tokens.enabled());
    }

    @PutMapping("/admin/settings/api")
    @Transactional
    public ApiSettings saveApiSettings(@RequestBody Map<String, Object> in) {
        admin();
        settings.put("api.tokens_enabled", String.valueOf(Boolean.TRUE.equals(In.bool(in, "tokensEnabled"))), current.ctx().userId());
        return new ApiSettings(tokens.enabled());
    }

    /** The integration API's description (OpenAPI 3.1): the main routes, how to authenticate, the error shape. */
    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> openApi() throws IOException {
        try (var in = new ClassPathResource("api/openapi.json").getInputStream()) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(in.readAllBytes());
        }
    }
}
