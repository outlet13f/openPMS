package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_CONNECTIONS;
import static com.openpms.db.Tables.CICD_CONNECTION_PROJECTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_COMMITS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_HEADS;
import static com.openpms.db.Tables.CICD_PIPELINES;
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
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Repositories on a project: search the tool, attach, detach. Remote calls never run inside a transaction: each
 * statement here is its own short one, so no DB connection is held while we wait for the code host.
 * A project sees only the connections whose allowed list holds it (V27); for any other the answer is the same 404 as for a
 * connection that is off or missing.
 */
@Service
public class CicdRepositories {
    private static final Logger LOG = LoggerFactory.getLogger(CicdRepositories.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("openpms.audit");

    public record ConnectionChoice(UUID id, String kind, String name) {}

    public record RepositoryDto(UUID id, String connectionName, String kind, String fullPath, String webUrl, OffsetDateTime lastPolledAt,
                               OffsetDateTime detachedAt) {}

    public record CleanupCount(long count) {}

    public record CleanupDone(long deleted) {}

    private final DSLContext db;
    private final SecretBox box;
    private final Adapters adapters;
    private final TransactionTemplate tx;

    public CicdRepositories(DSLContext db, SecretBox box, Adapters adapters, TransactionTemplate tx) {
        this.db = db;
        this.box = box;
        this.adapters = adapters;
        this.tx = tx;
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

    /** The active connections that allow this project. */
    public List<ConnectionChoice> connections(ProjectRow p) {
        return db.selectFrom(CICD_CONNECTIONS).where(CICD_CONNECTIONS.ACTIVE.isTrue()).and(allows(CICD_CONNECTIONS.ID, p.id()))
                .orderBy(CICD_CONNECTIONS.NAME).fetch(r -> new ConnectionChoice(r.getId(), r.getKind(), r.getName()));
    }

    private static org.jooq.Condition allows(org.jooq.Field<UUID> connectionId, UUID projectId) {
        return DSL.exists(DSL.selectOne().from(CICD_CONNECTION_PROJECTS).where(CICD_CONNECTION_PROJECTS.CONNECTION_ID.eq(connectionId))
                .and(CICD_CONNECTION_PROJECTS.PROJECT_ID.eq(projectId)));
    }

    public List<RepositoryDto> list(ProjectRow p) {
        return db.select(CICD_REPOSITORIES.ID, CICD_CONNECTIONS.NAME, CICD_CONNECTIONS.KIND, CICD_REPOSITORIES.FULL_PATH,
                        CICD_REPOSITORIES.WEB_URL, CICD_REPOSITORIES.LAST_POLLED_AT, CICD_REPOSITORIES.DETACHED_AT)
                .from(CICD_REPOSITORIES).join(CICD_CONNECTIONS).on(CICD_CONNECTIONS.ID.eq(CICD_REPOSITORIES.CONNECTION_ID))
                .where(CICD_REPOSITORIES.PROJECT_ID.eq(p.id())).orderBy(CICD_REPOSITORIES.DETACHED_AT.asc().nullsFirst(), CICD_REPOSITORIES.FULL_PATH)
                .fetch(r -> new RepositoryDto(r.get(CICD_REPOSITORIES.ID), r.get(CICD_CONNECTIONS.NAME), r.get(CICD_CONNECTIONS.KIND),
                        r.get(CICD_REPOSITORIES.FULL_PATH), r.get(CICD_REPOSITORIES.WEB_URL), r.get(CICD_REPOSITORIES.LAST_POLLED_AT),
                        r.get(CICD_REPOSITORIES.DETACHED_AT)));
    }

    /** attachedProject appears (true) only for repositories attached somewhere now (a detached one is free); never which project. */
    public List<Map<String, Object>> search(ProjectRow p, UUID connectionId, String query) {
        if (query != null && query.length() > 100) throw ApiException.field("q", "검색어가 너무 깁니다.");
        var c = activeConnection(connectionId, p);
        var found = remote(c, query);
        var out = new java.util.ArrayList<Map<String, Object>>();
        for (var r : found) {
            var m = new LinkedHashMap<String, Object>();
            m.put("externalId", r.externalId());
            m.put("fullPath", r.fullPath());
            m.put("webUrl", r.webUrl());
            if (db.fetchExists(CICD_REPOSITORIES, CICD_REPOSITORIES.CONNECTION_ID.eq(c.getId()).and(CICD_REPOSITORIES.EXTERNAL_ID.eq(r.externalId())
                    .and(CICD_REPOSITORIES.DETACHED_AT.isNull()))))
                m.put("attachedProject", true);
            out.add(m);
        }
        return out;
    }

    public RepositoryDto attach(Ctx ctx, ProjectRow p, Map<String, Object> in) {
        var connectionId = uuid(In.str(in, "connectionId"));
        var externalId = In.str(in, "externalId");
        if (externalId == null || externalId.isBlank() || externalId.length() > 200) throw ApiException.field("externalId", "저장소를 고르세요.");
        var c = activeConnection(connectionId, p);
        var existing = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(c.getId()))
                .and(CICD_REPOSITORIES.EXTERNAL_ID.eq(externalId)).fetchOne();
        if (existing != null && existing.getDetachedAt() == null) throw taken(existing.getProjectId(), p);
        // what we store is what the host says; anything else in the body (fullPath, webUrl) is ignored
        var repo = lookup(c, externalId);
        if (repo == null) throw ApiException.unprocessable("연동한 도구에서 저장소를 찾을 수 없습니다.", null);
        var cursor = OffsetDateTime.now().minusDays(7);
        UUID repoId;
        try {
            // the list may have changed while we asked the host: check again, holding the row so that taking the project off
            // the list (CicdConnections.setProjects) waits for this write and then refuses with 409, or wins and we answer 404
            repoId = tx.execute(s -> {
                var held = db.selectOne().from(CICD_CONNECTION_PROJECTS).where(CICD_CONNECTION_PROJECTS.CONNECTION_ID.eq(c.getId()))
                        .and(CICD_CONNECTION_PROJECTS.PROJECT_ID.eq(p.id())).forShare().fetchOne();
                if (held == null) throw ApiException.notFound("연동을 찾을 수 없습니다.");
                if (existing != null) {
                    // a detached row: the same row comes back (history kept), here, whichever project it was on
                    int n = db.update(CICD_REPOSITORIES).set(CICD_REPOSITORIES.PROJECT_ID, p.id()).set(CICD_REPOSITORIES.FULL_PATH, repo.fullPath())
                            .set(CICD_REPOSITORIES.WEB_URL, repo.webUrl()).set(CICD_REPOSITORIES.DETACHED_AT, (OffsetDateTime) null)
                            .set(CICD_REPOSITORIES.POLL_CURSOR, cursor)
                            .where(CICD_REPOSITORIES.ID.eq(existing.getId()), CICD_REPOSITORIES.DETACHED_AT.isNotNull()).execute();
                    if (n == 0) {
                        var now = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(existing.getId())).fetchOne();
                        throw taken(now == null ? existing.getProjectId() : now.getProjectId(), p);
                    }
                    if (!existing.getProjectId().equals(p.id())) {
                        // the environments belong to the old project: what they hold of this repository goes; builds and deployments stay
                        db.deleteFrom(CICD_ENVIRONMENT_HEADS).where(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID.eq(existing.getId())).execute();
                        db.deleteFrom(CICD_ENVIRONMENT_COMMITS).where(CICD_ENVIRONMENT_COMMITS.REPOSITORY_ID.eq(existing.getId())).execute();
                    }
                    return existing.getId();
                }
                var r = db.newRecord(CICD_REPOSITORIES);
                r.setConnectionId(c.getId());
                r.setExternalId(repo.externalId());
                r.setFullPath(repo.fullPath());
                r.setWebUrl(repo.webUrl());
                r.setProjectId(p.id());
                r.setPollCursor(cursor);
                r.insert();
                return r.getId();
            });
        } catch (DataIntegrityViolationException e) {
            var owner = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(c.getId()))
                    .and(CICD_REPOSITORIES.EXTERNAL_ID.eq(repo.externalId())).fetchOne();
            if (owner == null) throw e;
            throw taken(owner.getProjectId(), p);
        }
        if (existing == null) AUDIT.info("cicd.repository.attached by={} project={} connection={} repository={}", ctx.userId(), p.id(), c.getId(), repoId);
        else if (existing.getProjectId().equals(p.id())) AUDIT.info("cicd.repository.reattached by={} project={} repository={}", ctx.userId(), p.id(), repoId);
        else AUDIT.info("cicd.repository.moved by={} from={} to={} repository={}", ctx.userId(), existing.getProjectId(), p.id(), repoId);
        return list(p).stream().filter(d -> d.id().equals(repoId)).findFirst().orElseThrow();
    }

    /** Soft detach: the row, commits, merge requests, links, builds and deployments stay; only new events stop coming in. */
    public void detach(Ctx ctx, ProjectRow p, UUID repoId) {
        int n = db.update(CICD_REPOSITORIES).set(CICD_REPOSITORIES.DETACHED_AT, OffsetDateTime.now())
                .where(CICD_REPOSITORIES.ID.eq(repoId), CICD_REPOSITORIES.PROJECT_ID.eq(p.id()), CICD_REPOSITORIES.DETACHED_AT.isNull()).execute();
        if (n == 0) throw ApiException.notFound("저장소를 찾을 수 없습니다.");
        AUDIT.info("cicd.repository.detached by={} project={} repository={}", ctx.userId(), p.id(), repoId);
    }

    /** Takes a detached repository of this project back as the same row. No call to the host, so a connection without a token works. */
    public RepositoryDto reattach(Ctx ctx, ProjectRow p, UUID repoId) {
        var row = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(repoId), CICD_REPOSITORIES.PROJECT_ID.eq(p.id()),
                CICD_REPOSITORIES.DETACHED_AT.isNotNull()).fetchOne();
        if (row == null) throw ApiException.notFound("저장소를 찾을 수 없습니다.");
        tx.executeWithoutResult(s -> {
            var held = db.selectOne().from(CICD_CONNECTION_PROJECTS).where(CICD_CONNECTION_PROJECTS.CONNECTION_ID.eq(row.getConnectionId()))
                    .and(CICD_CONNECTION_PROJECTS.PROJECT_ID.eq(p.id())).forShare().fetchOne();
            var active = db.fetchExists(CICD_CONNECTIONS, CICD_CONNECTIONS.ID.eq(row.getConnectionId()).and(CICD_CONNECTIONS.ACTIVE.isTrue()));
            if (held == null || !active) throw ApiException.notFound("연동을 찾을 수 없습니다.");
            int n = db.update(CICD_REPOSITORIES).set(CICD_REPOSITORIES.DETACHED_AT, (OffsetDateTime) null)
                    .set(CICD_REPOSITORIES.POLL_CURSOR, OffsetDateTime.now().minusDays(7))
                    .where(CICD_REPOSITORIES.ID.eq(repoId), CICD_REPOSITORIES.DETACHED_AT.isNotNull()).execute();
            if (n == 0) throw ApiException.notFound("저장소를 찾을 수 없습니다.");
        });
        AUDIT.info("cicd.repository.reattached by={} project={} repository={}", ctx.userId(), p.id(), repoId);
        return list(p).stream().filter(d -> d.id().equals(repoId)).findFirst().orElseThrow();
    }

    private static final java.time.ZoneId SEOUL = java.time.ZoneId.of("Asia/Seoul");

    /** 00:00 Asia/Seoul of the chosen day; a missing or non-date value is 422, and so is a day after today. */
    private static OffsetDateTime cutoff(String before) {
        java.time.LocalDate day;
        try {
            day = before == null ? null : java.time.LocalDate.parse(before);
        } catch (java.time.format.DateTimeParseException e) {
            day = null;
        }
        if (day == null) throw ApiException.field("before", "날짜를 고르세요.");
        if (day.isAfter(java.time.LocalDate.now(SEOUL))) throw ApiException.field("before", "오늘이나 그 앞 날짜를 고르세요.");
        return day.atStartOfDay(SEOUL).toOffsetDateTime();
    }

    /** The project's builds (of its repositories, detached ones too) that ended, or were last heard of, before the cutoff. */
    private org.jooq.Condition oldBuilds(ProjectRow p, OffsetDateTime cutoff) {
        return CICD_PIPELINES.REPOSITORY_ID.in(DSL.select(CICD_REPOSITORIES.ID).from(CICD_REPOSITORIES).where(CICD_REPOSITORIES.PROJECT_ID.eq(p.id())))
                .and(DSL.coalesce(CICD_PIPELINES.FINISHED_AT, CICD_PIPELINES.UPDATED_AT).lt(cutoff));
    }

    public CleanupCount cleanupCount(ProjectRow p, String before) {
        return new CleanupCount(db.fetchCount(CICD_PIPELINES, oldBuilds(p, cutoff(before))));
    }

    /** Deletes build records only; deployments and environments stay. */
    public CleanupDone cleanup(Ctx ctx, ProjectRow p, String before) {
        var cut = cutoff(before);
        int n = db.deleteFrom(CICD_PIPELINES).where(oldBuilds(p, cut)).execute();
        AUDIT.info("cicd.pipelines.cleaned by={} project={} before={} deleted={}", ctx.userId(), p.id(), before, n);
        return new CleanupDone(n);
    }

    private static ApiException taken(UUID owner, ProjectRow p) {
        return new ApiException(409, owner.equals(p.id()) ? "이미 이 프로젝트에 붙은 저장소입니다." : "이미 다른 프로젝트에 붙은 저장소입니다.");
    }

    private com.openpms.db.tables.records.CicdConnectionsRecord activeConnection(UUID id, ProjectRow p) {
        var c = id == null ? null : db.selectFrom(CICD_CONNECTIONS).where(CICD_CONNECTIONS.ID.eq(id)).and(allows(CICD_CONNECTIONS.ID, p.id())).fetchOne();
        if (c == null || !Boolean.TRUE.equals(c.getActive())) throw ApiException.notFound("연동을 찾을 수 없습니다.");
        if (c.getApiTokenEnc() == null) throw ApiException.unprocessable("이 연동에는 API 토큰이 없어 저장소를 찾을 수 없습니다.", null);
        return c;
    }

    private CicdAdapter.RemoteRepo lookup(com.openpms.db.tables.records.CicdConnectionsRecord c, String externalId) {
        try {
            return adapters.of(c.getKind()).repository(new CicdAdapter.Remote(c.getBaseUrl(), box.open(c.getApiTokenEnc())), externalId);
        } catch (IOException | RuntimeException e) {
            throw remoteFailed(c, e);
        }
    }

    private static ApiException remoteFailed(com.openpms.db.tables.records.CicdConnectionsRecord c, Exception e) {
        if (e instanceof ApiException a) return a;
        // class name only: the message may carry the address or the other side's text
        LOG.warn("cicd remote call failed connection={} error={}", c.getId(), e.getClass().getSimpleName());
        return new ApiException(502, "연동한 도구에 연결하지 못했습니다.");
    }

    private List<CicdAdapter.RemoteRepo> remote(com.openpms.db.tables.records.CicdConnectionsRecord c, String query) {
        try {
            var remote = new CicdAdapter.Remote(c.getBaseUrl(), box.open(c.getApiTokenEnc()));
            return adapters.of(c.getKind()).listRepositories(remote, query);
        } catch (IOException | RuntimeException e) {
            throw remoteFailed(c, e);
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
