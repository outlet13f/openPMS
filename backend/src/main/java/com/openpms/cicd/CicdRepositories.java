package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_CONNECTIONS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;

import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Snapshot.ProjectRow;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Repositories on a project: search the tool, attach, detach. Remote calls never run inside a transaction: each
 * statement here is its own short one, so no DB connection is held while we wait for the code host.
 */
@Service
public class CicdRepositories {
    private static final Logger AUDIT = LoggerFactory.getLogger("openpms.audit");

    public record ConnectionChoice(UUID id, String kind, String name) {}

    public record RepositoryDto(UUID id, String connectionName, String kind, String fullPath, String webUrl, OffsetDateTime lastPolledAt) {}

    private final DSLContext db;
    private final SecretBox box;
    private final Adapters adapters;

    public CicdRepositories(DSLContext db, SecretBox box, Adapters adapters) {
        this.db = db;
        this.box = box;
        this.adapters = adapters;
    }

    /** Project visible with the module on and cicd.view, else 404 (a non-member must not learn it exists). */
    public ProjectRow viewable(Ctx ctx, String idOrIdentifier) {
        var p = ctx.findProject(idOrIdentifier);
        if (!ctx.can(p, "cicd.view")) throw ApiException.notFound("프로젝트를 찾을 수 없습니다.");
        return p;
    }

    public ProjectRow manageable(Ctx ctx, String idOrIdentifier) {
        var p = viewable(ctx, idOrIdentifier);
        ctx.require(p, "cicd.manage", "저장소를 관리할 권한이 없습니다.");
        return p;
    }

    public List<ConnectionChoice> connections() {
        return db.selectFrom(CICD_CONNECTIONS).where(CICD_CONNECTIONS.ACTIVE.isTrue()).orderBy(CICD_CONNECTIONS.NAME)
                .fetch(r -> new ConnectionChoice(r.getId(), r.getKind(), r.getName()));
    }

    public List<RepositoryDto> list(ProjectRow p) {
        return db.select(CICD_REPOSITORIES.ID, CICD_CONNECTIONS.NAME, CICD_CONNECTIONS.KIND, CICD_REPOSITORIES.FULL_PATH,
                        CICD_REPOSITORIES.WEB_URL, CICD_REPOSITORIES.LAST_POLLED_AT)
                .from(CICD_REPOSITORIES).join(CICD_CONNECTIONS).on(CICD_CONNECTIONS.ID.eq(CICD_REPOSITORIES.CONNECTION_ID))
                .where(CICD_REPOSITORIES.PROJECT_ID.eq(p.id())).orderBy(CICD_REPOSITORIES.FULL_PATH)
                .fetch(r -> new RepositoryDto(r.get(CICD_REPOSITORIES.ID), r.get(CICD_CONNECTIONS.NAME), r.get(CICD_CONNECTIONS.KIND),
                        r.get(CICD_REPOSITORIES.FULL_PATH), r.get(CICD_REPOSITORIES.WEB_URL), r.get(CICD_REPOSITORIES.LAST_POLLED_AT)));
    }

    /** attachedProject appears (true) only for repositories already attached somewhere; never which project. */
    public List<Map<String, Object>> search(UUID connectionId, String query) {
        var c = activeConnection(connectionId);
        var found = remote(c, query);
        var out = new java.util.ArrayList<Map<String, Object>>();
        for (var r : found) {
            var m = new LinkedHashMap<String, Object>();
            m.put("externalId", r.externalId());
            m.put("fullPath", r.fullPath());
            m.put("webUrl", r.webUrl());
            if (db.fetchExists(CICD_REPOSITORIES, CICD_REPOSITORIES.CONNECTION_ID.eq(c.getId()).and(CICD_REPOSITORIES.EXTERNAL_ID.eq(r.externalId()))))
                m.put("attachedProject", true);
            out.add(m);
        }
        return out;
    }

    public RepositoryDto attach(Ctx ctx, ProjectRow p, Map<String, Object> in) {
        var connectionId = uuid(In.str(in, "connectionId"));
        var externalId = In.str(in, "externalId");
        if (externalId == null || externalId.isBlank() || externalId.length() > 200) throw ApiException.field("externalId", "저장소를 고르세요.");
        var c = activeConnection(connectionId);
        var existing = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(c.getId()))
                .and(CICD_REPOSITORIES.EXTERNAL_ID.eq(externalId)).fetchOne();
        if (existing != null) throw taken(existing.getProjectId(), p);
        // the client's fullPath, if any, only narrows the search; what we store is what the host says
        var hint = In.str(in, "fullPath");
        var repo = find(c, externalId, hint);
        if (repo == null) repo = find(c, externalId, null);
        if (repo == null) throw ApiException.unprocessable("연동한 도구에서 저장소를 찾을 수 없습니다.", null);
        var r = db.newRecord(CICD_REPOSITORIES);
        r.setConnectionId(c.getId());
        r.setExternalId(repo.externalId());
        r.setFullPath(repo.fullPath());
        r.setWebUrl(repo.webUrl());
        r.setProjectId(p.id());
        r.setPollCursor(OffsetDateTime.now().minusDays(7));
        try {
            r.insert();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(409, "이미 다른 프로젝트에 붙은 저장소입니다.");
        }
        AUDIT.info("cicd.repository.attached by={} project={} connection={} repository={}", ctx.userId(), p.id(), c.getId(), r.getId());
        return list(p).stream().filter(d -> d.id().equals(r.getId())).findFirst().orElseThrow();
    }

    public void detach(Ctx ctx, ProjectRow p, UUID repoId) {
        int n = db.deleteFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(repoId)).and(CICD_REPOSITORIES.PROJECT_ID.eq(p.id())).execute();
        if (n == 0) throw ApiException.notFound("저장소를 찾을 수 없습니다.");
        AUDIT.info("cicd.repository.detached by={} project={} repository={}", ctx.userId(), p.id(), repoId);
    }

    private static ApiException taken(UUID owner, ProjectRow p) {
        return new ApiException(409, owner.equals(p.id()) ? "이미 이 프로젝트에 붙은 저장소입니다." : "이미 다른 프로젝트에 붙은 저장소입니다.");
    }

    private com.openpms.db.tables.records.CicdConnectionsRecord activeConnection(UUID id) {
        var c = id == null ? null : db.selectFrom(CICD_CONNECTIONS).where(CICD_CONNECTIONS.ID.eq(id)).fetchOne();
        if (c == null || !Boolean.TRUE.equals(c.getActive())) throw ApiException.notFound("연동을 찾을 수 없습니다.");
        if (c.getApiTokenEnc() == null) throw ApiException.unprocessable("이 연동에는 API 토큰이 없어 저장소를 찾을 수 없습니다.", null);
        return c;
    }

    private CicdAdapter.RemoteRepo find(com.openpms.db.tables.records.CicdConnectionsRecord c, String externalId, String hint) {
        String query = null;
        if (hint != null && !hint.isBlank()) {
            var h = hint.strip();
            query = h.substring(h.lastIndexOf('/') + 1);
        }
        for (var r : remote(c, query)) if (r.externalId().equals(externalId)) return r;
        return null;
    }

    private List<CicdAdapter.RemoteRepo> remote(com.openpms.db.tables.records.CicdConnectionsRecord c, String query) {
        try {
            var remote = new CicdAdapter.Remote(c.getBaseUrl(), box.open(c.getApiTokenEnc()));
            return adapters.of(c.getKind()).listRepositories(remote, query);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) throw a;
            throw new ApiException(502, "연동한 도구에 연결하지 못했습니다.");
        }
    }

    private static UUID uuid(String v) {
        try {
            return v == null ? null : UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
