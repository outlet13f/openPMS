package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;

import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Snapshot.ProjectRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's environments (dev, staging, production, ...) and the aliases a code host or deploy tool may call them. Names and
 * aliases match case-insensitively; a hidden environment (it has deployment history, so it cannot be deleted) takes no new
 * deployments. Adding an alias takes the project's unassigned deployments with that name into the environment.
 */
@Service
public class CicdEnvironments {
    private static final Logger AUDIT = LoggerFactory.getLogger("openpms.audit");
    private static final int UNASSIGNED_LIMIT = 50;

    public record EnvironmentDto(UUID id, String name, int position, boolean production, List<String> aliases, boolean hidden, int deploymentCount) {}

    public record UnassignedDto(String name, int count, OffsetDateTime lastAt) {}

    public record EnvironmentsView(List<EnvironmentDto> environments, List<UnassignedDto> unassigned, boolean canManage) {}

    public record Removed(boolean deleted) {}

    private record Default(String name, int position, boolean production, String[] aliases) {}

    /** The same three V28 gives to the projects that already use the module. */
    private static final List<Default> DEFAULTS = List.of(new Default("개발", 1, false, new String[] {"dev", "development"}),
            new Default("스테이징", 2, false, new String[] {"stg", "staging"}), new Default("운영", 3, true, new String[] {"prod", "production"}));

    private final DSLContext db;
    private final CicdRepositories repos;

    public CicdEnvironments(DSLContext db, CicdRepositories repos) {
        this.db = db;
        this.repos = repos;
    }

    /* ---------- access ---------- */

    public ProjectRow viewable(Ctx ctx, String project) {
        return repos.viewable(ctx, project);
    }

    public ProjectRow manageable(Ctx ctx, String project) {
        var p = repos.viewable(ctx, project);
        ctx.require(p, "cicd.manage", "환경을 관리할 권한이 없습니다.");
        return p;
    }

    /* ---------- read ---------- */

    public EnvironmentsView view(Ctx ctx, ProjectRow p) {
        return new EnvironmentsView(list(p.id()), unassigned(p.id()), ctx.can(p, "cicd.manage"));
    }

    private List<EnvironmentDto> list(UUID projectId) {
        var counts = db.select(CICD_DEPLOYMENTS.ENVIRONMENT_ID, DSL.count()).from(CICD_DEPLOYMENTS).join(CICD_ENVIRONMENTS)
                .on(CICD_ENVIRONMENTS.ID.eq(CICD_DEPLOYMENTS.ENVIRONMENT_ID)).where(CICD_ENVIRONMENTS.PROJECT_ID.eq(projectId))
                .groupBy(CICD_DEPLOYMENTS.ENVIRONMENT_ID).fetchMap(CICD_DEPLOYMENTS.ENVIRONMENT_ID, DSL.count());
        return db.selectFrom(CICD_ENVIRONMENTS).where(CICD_ENVIRONMENTS.PROJECT_ID.eq(projectId))
                .orderBy(CICD_ENVIRONMENTS.POSITION, CICD_ENVIRONMENTS.NAME)
                .fetch(r -> dto(r.getId(), r.getName(), r.getPosition(), r.getProduction(), r.getAliases(), r.getHidden(),
                        counts.getOrDefault(r.getId(), 0)));
    }

    private static EnvironmentDto dto(UUID id, String name, int position, boolean production, String[] aliases, boolean hidden, int count) {
        return new EnvironmentDto(id, name, position, production, List.of(aliases), hidden, count);
    }

    private EnvironmentDto one(UUID id) {
        var r = db.selectFrom(CICD_ENVIRONMENTS).where(CICD_ENVIRONMENTS.ID.eq(id)).fetchOne();
        int n = db.fetchCount(CICD_DEPLOYMENTS, CICD_DEPLOYMENTS.ENVIRONMENT_ID.eq(id));
        return dto(r.getId(), r.getName(), r.getPosition(), r.getProduction(), r.getAliases(), r.getHidden(), n);
    }

    /** Deployments of this project's repositories (detached ones too) that no environment took, grouped by name ignoring case. */
    private List<UnassignedDto> unassigned(UUID projectId) {
        var name = DSL.lower(CICD_DEPLOYMENTS.ENVIRONMENT_NAME);
        var count = DSL.count();
        var last = DSL.max(CICD_DEPLOYMENTS.DEPLOYED_AT);
        return db.select(name, count, last).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ENVIRONMENT_ID.isNull())
                .and(CICD_DEPLOYMENTS.REPOSITORY_ID.in(DSL.select(CICD_REPOSITORIES.ID).from(CICD_REPOSITORIES).where(CICD_REPOSITORIES.PROJECT_ID.eq(projectId))))
                .groupBy(name).orderBy(count.desc(), name).limit(UNASSIGNED_LIMIT).fetch(r -> new UnassignedDto(r.value1(), r.value2(), r.value3()));
    }

    /* ---------- write ---------- */

    @Transactional
    public EnvironmentDto create(Ctx ctx, ProjectRow p, Map<String, Object> in) {
        var name = name(In.str(in, "name"));
        var aliases = In.has(in, "aliases") ? aliases(in.get("aliases")) : List.<String>of();
        lock(p.id());
        var others = others(p.id(), null);
        if (others.stream().anyMatch(o -> o.name().equalsIgnoreCase(name))) throw sameName();
        checkClash(others, name, aliases);
        int position = db.select(DSL.coalesce(DSL.max(CICD_ENVIRONMENTS.POSITION), 0)).from(CICD_ENVIRONMENTS)
                .where(CICD_ENVIRONMENTS.PROJECT_ID.eq(p.id())).fetchOne(0, Integer.class) + 1;
        UUID id;
        try {
            id = db.insertInto(CICD_ENVIRONMENTS).set(CICD_ENVIRONMENTS.PROJECT_ID, p.id()).set(CICD_ENVIRONMENTS.NAME, name)
                    .set(CICD_ENVIRONMENTS.POSITION, position).set(CICD_ENVIRONMENTS.PRODUCTION, Boolean.TRUE.equals(In.bool(in, "production")))
                    .set(CICD_ENVIRONMENTS.ALIASES, aliases.toArray(String[]::new)).returning(CICD_ENVIRONMENTS.ID).fetchOne().getId();
        } catch (DataIntegrityViolationException e) {
            throw sameName();
        }
        AUDIT.info("cicd.environment.created by={} project={} environment={} name={}", ctx.userId(), p.id(), id, name);
        var names = new LinkedHashSet<String>();
        names.add(name.toLowerCase(Locale.ROOT));
        names.addAll(aliases);
        reassign(p.id(), id, List.copyOf(names));
        return one(id);
    }

    @Transactional
    public EnvironmentDto update(Ctx ctx, ProjectRow p, UUID id, Map<String, Object> in) {
        lock(p.id());
        var env = find(p.id(), id);
        var others = others(p.id(), id);
        var name = In.has(in, "name") ? name(In.str(in, "name")) : env.getName();
        if (In.has(in, "name")) {
            if (others.stream().anyMatch(o -> o.name().equalsIgnoreCase(name))) throw sameName();
        }
        var oldAliases = List.of(env.getAliases());
        var aliases = In.has(in, "aliases") ? aliases(in.get("aliases")) : oldAliases;
        checkClash(others, name, aliases);
        boolean production = In.has(in, "production") ? Boolean.TRUE.equals(In.bool(in, "production")) : env.getProduction();
        boolean hidden = In.has(in, "hidden") ? Boolean.TRUE.equals(In.bool(in, "hidden")) : env.getHidden();
        try {
            db.update(CICD_ENVIRONMENTS).set(CICD_ENVIRONMENTS.NAME, name).set(CICD_ENVIRONMENTS.PRODUCTION, production)
                    .set(CICD_ENVIRONMENTS.HIDDEN, hidden).set(CICD_ENVIRONMENTS.ALIASES, aliases.toArray(String[]::new))
                    .where(CICD_ENVIRONMENTS.ID.eq(id)).execute();
        } catch (DataIntegrityViolationException e) {
            throw sameName();
        }
        boolean otherChange = !name.equals(env.getName()) || production != env.getProduction() || (hidden != env.getHidden() && !hidden);
        if (otherChange) AUDIT.info("cicd.environment.updated by={} project={} environment={} name={}", ctx.userId(), p.id(), id, name);
        if (hidden && !env.getHidden()) AUDIT.info("cicd.environment.hidden by={} project={} environment={} name={}", ctx.userId(), p.id(), id, name);
        var added = aliases.stream().filter(a -> !oldAliases.contains(a)).toList();
        var removed = oldAliases.stream().filter(a -> !aliases.contains(a)).toList();
        if (!added.isEmpty() || !removed.isEmpty()) {
            AUDIT.info("cicd.environment.aliases_changed by={} project={} environment={} added={} removed={}", ctx.userId(), p.id(), id,
                    added.size(), removed.size());
        }
        if (!hidden) {
            // what the environment now answers to that it did not before (a new alias, a new name, or everything once visible again)
            var names = new LinkedHashSet<String>();
            if (env.getHidden()) {
                names.add(name.toLowerCase(Locale.ROOT));
                names.addAll(aliases);
            } else {
                if (!name.equalsIgnoreCase(env.getName())) names.add(name.toLowerCase(Locale.ROOT));
                names.addAll(added);
            }
            if (!names.isEmpty()) reassign(p.id(), id, List.copyOf(names));
        }
        return one(id);
    }

    @Transactional
    public EnvironmentsView reorder(Ctx ctx, ProjectRow p, Map<String, Object> in) {
        lock(p.id());
        var given = In.strings(in, "ids");
        var bad = ApiException.field("ids", "환경 순서가 올바르지 않습니다.");
        if (given == null) throw bad;
        var ids = new ArrayList<UUID>();
        for (var s : given) {
            var u = s == null ? null : In.uuidOrNull(s);
            if (u == null) throw bad;
            ids.add(u);
        }
        var all = new HashSet<>(db.select(CICD_ENVIRONMENTS.ID).from(CICD_ENVIRONMENTS).where(CICD_ENVIRONMENTS.PROJECT_ID.eq(p.id()))
                .fetch(CICD_ENVIRONMENTS.ID));
        if (ids.size() != all.size() || !all.equals(new HashSet<>(ids))) throw bad;
        for (int i = 0; i < ids.size(); i++) {
            db.update(CICD_ENVIRONMENTS).set(CICD_ENVIRONMENTS.POSITION, i + 1).where(CICD_ENVIRONMENTS.ID.eq(ids.get(i))).execute();
        }
        AUDIT.info("cicd.environment.reordered by={} project={} environment={} name={}", ctx.userId(), p.id(), "-", "-");
        return view(ctx, p);
    }

    /** Deletes the environment, or hides it when it has deployment history. */
    @Transactional
    public Removed remove(Ctx ctx, ProjectRow p, UUID id) {
        lock(p.id());
        var env = find(p.id(), id);
        if (db.fetchExists(CICD_DEPLOYMENTS, CICD_DEPLOYMENTS.ENVIRONMENT_ID.eq(id))) {
            db.update(CICD_ENVIRONMENTS).set(CICD_ENVIRONMENTS.HIDDEN, true).where(CICD_ENVIRONMENTS.ID.eq(id)).execute();
            AUDIT.info("cicd.environment.hidden by={} project={} environment={} name={}", ctx.userId(), p.id(), id, env.getName());
            return new Removed(false);
        }
        db.deleteFrom(CICD_ENVIRONMENTS).where(CICD_ENVIRONMENTS.ID.eq(id)).execute();
        AUDIT.info("cicd.environment.deleted by={} project={} environment={} name={}", ctx.userId(), p.id(), id, env.getName());
        return new Removed(true);
    }

    /* ---------- defaults and matching ---------- */

    /** Inserts 개발·스테이징·운영 when the project has no environment at all (module just switched on, or a copy). */
    @Transactional
    public void ensureDefaults(UUID projectId) {
        lock(projectId);
        if (db.fetchExists(CICD_ENVIRONMENTS, CICD_ENVIRONMENTS.PROJECT_ID.eq(projectId))) return;
        for (var d : DEFAULTS) {
            db.insertInto(CICD_ENVIRONMENTS).set(CICD_ENVIRONMENTS.PROJECT_ID, projectId).set(CICD_ENVIRONMENTS.NAME, d.name())
                    .set(CICD_ENVIRONMENTS.POSITION, d.position()).set(CICD_ENVIRONMENTS.PRODUCTION, d.production())
                    .set(CICD_ENVIRONMENTS.ALIASES, d.aliases()).onConflictDoNothing().execute();
        }
    }

    /** The visible environment whose name or alias is this (ignoring case), or null: the deployment stays unassigned. */
    public UUID match(UUID projectId, String toolEnvironment) {
        if (toolEnvironment == null) return null;
        var x = toolEnvironment.toLowerCase(Locale.ROOT);
        return db.select(CICD_ENVIRONMENTS.ID).from(CICD_ENVIRONMENTS).where(CICD_ENVIRONMENTS.PROJECT_ID.eq(projectId))
                .and(CICD_ENVIRONMENTS.HIDDEN.isFalse())
                .and(DSL.lower(CICD_ENVIRONMENTS.NAME).eq(x).or(DSL.condition("{0} = any({1})", DSL.val(x), CICD_ENVIRONMENTS.ALIASES)))
                .orderBy(CICD_ENVIRONMENTS.POSITION).limit(1).fetchOne(CICD_ENVIRONMENTS.ID);
    }

    /**
     * An alias was added: this project's unassigned deployments with that name join the environment. Only repositories whose
     * current project is this one count (detached ones too), so deployments of a repository now elsewhere are never touched.
     * Their effect on the environment's head is applied in time order by the deployment advance, which records a deployment
     * only when a later one is already the head.
     */
    void reassign(UUID projectId, UUID environmentId, List<String> addedNames) {
        var inclusion = DSL.when(CICD_DEPLOYMENTS.STATUS.eq("success").and(CICD_DEPLOYMENTS.SHA.isNull()), DSL.inline("unknown_commit"))
                .when(CICD_DEPLOYMENTS.STATUS.eq("success"), DSL.inline("checking")).otherwise(DSL.inline((String) null));
        db.update(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.ENVIRONMENT_ID, environmentId).set(CICD_DEPLOYMENTS.INCLUSION, inclusion)
                .where(CICD_DEPLOYMENTS.ENVIRONMENT_ID.isNull())
                .and(CICD_DEPLOYMENTS.REPOSITORY_ID.in(DSL.select(CICD_REPOSITORIES.ID).from(CICD_REPOSITORIES).where(CICD_REPOSITORIES.PROJECT_ID.eq(projectId))))
                .and(DSL.lower(CICD_DEPLOYMENTS.ENVIRONMENT_NAME).in(addedNames)).execute();
    }

    /* ---------- helpers ---------- */

    /** Serializes this project's environment edits (names and aliases must not clash, whoever writes). */
    private void lock(UUID projectId) {
        db.execute("select pg_advisory_xact_lock(hashtextextended(?, 0))", "cicd-env:" + projectId);
    }

    private record Other(String name, Set<String> aliases) {}

    private List<Other> others(UUID projectId, UUID except) {
        var q = db.selectFrom(CICD_ENVIRONMENTS).where(CICD_ENVIRONMENTS.PROJECT_ID.eq(projectId));
        if (except != null) q = q.and(CICD_ENVIRONMENTS.ID.ne(except));
        return q.fetch(r -> new Other(r.getName(), new HashSet<>(List.of(r.getAliases()))));
    }

    /** A name or alias another environment of the project already answers to is refused: it would match two. */
    private static void checkClash(List<Other> others, String name, List<String> aliases) {
        var lowerName = name.toLowerCase(Locale.ROOT);
        for (var o : others) {
            if (o.aliases().contains(lowerName) && !o.name().equalsIgnoreCase(name)) {
                throw ApiException.field("name", "다른 환경이 이미 쓰는 이름입니다: " + name);
            }
            for (var a : aliases) {
                if (o.name().toLowerCase(Locale.ROOT).equals(a) || o.aliases().contains(a)) {
                    throw ApiException.field("aliases", "다른 환경이 이미 쓰는 이름입니다: " + a);
                }
            }
        }
    }

    private com.openpms.db.tables.records.CicdEnvironmentsRecord find(UUID projectId, UUID id) {
        var r = db.selectFrom(CICD_ENVIRONMENTS).where(CICD_ENVIRONMENTS.ID.eq(id), CICD_ENVIRONMENTS.PROJECT_ID.eq(projectId)).fetchOne();
        if (r == null) throw ApiException.notFound("환경을 찾을 수 없습니다.");
        return r;
    }

    private static ApiException sameName() {
        return ApiException.conflict("같은 이름의 환경이 있습니다.", null, null);
    }

    private static boolean control(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }

    private static String name(String raw) {
        var n = raw == null ? "" : raw.strip();
        if (n.isEmpty() || n.length() > 50 || control(n)) throw ApiException.field("name", "환경 이름은 1자에서 50자 사이여야 합니다.");
        return n;
    }

    /** An array of strings, each 1-100 characters without commas or control characters; trimmed, lower-cased, no repeats, 20 at most. */
    static List<String> aliases(Object raw) {
        var bad = ApiException.field("aliases", "별칭이 올바르지 않습니다.");
        if (!(raw instanceof List<?> l)) throw bad;
        var out = new LinkedHashSet<String>();
        for (var o : l) {
            if (!(o instanceof String s)) throw bad;
            var a = s.strip().toLowerCase(Locale.ROOT);
            if (a.isEmpty() || a.length() > 100 || a.indexOf(',') >= 0 || control(a)) throw bad;
            out.add(a);
        }
        if (out.size() > 20) throw bad;
        return List.copyOf(out);
    }
}
