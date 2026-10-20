package com.openpms.cicd;

import com.openpms.api.Dto;
import com.openpms.cicd.CicdReleaseController.Board;
import com.openpms.cicd.CicdReleaseController.Card;
import com.openpms.cicd.CicdReleaseController.EnvironmentRef;
import com.openpms.cicd.CicdReleaseController.Excluded;
import com.openpms.cicd.CicdReleaseController.Missing;
import com.openpms.cicd.CicdReleaseController.Notice;
import com.openpms.cicd.CicdReleaseController.UnlinkedCommit;
import com.openpms.cicd.CicdReleaseController.VerificationDto;
import com.openpms.cicd.CicdReleaseController.VersionRef;
import com.openpms.common.ApiException;
import com.openpms.core.Ctx;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.core.Snapshot.ProjectRow;
import com.openpms.wp.Assignees;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * The release verification board of a project (design 4.1): the work packages whose commits are in the source environment S
 * but not yet in the target T, with their current verification, the version check and the banner. Read only, a fixed number
 * of queries however many cards there are, and no remote call.
 */
@Service
public class CicdReleaseBoard {
    static final int UNLINKED_LIMIT = 100;
    private static final List<String> COLUMNS = List.of("waiting", "reverify", "failed", "passed");

    public record Pair(UUID source, UUID target) {}

    private record Env(UUID id, String name, int position, boolean production, boolean hidden) {}

    /** The project's environments by position and its attached repositories. */
    private record Setup(List<Env> environments, List<UUID> repositories) {}

    /** A work package with a commit in the difference that this person may see: its newest such row. */
    private record Candidate(UUID id, long number, String subject, UUID statusId, UUID versionId, UUID projectId, OffsetDateTime deployedAt, String deploymentUrl) {}

    private record Unlinked(String repository, String sha, String title, String webUrl, OffsetDateTime deployedAt) {}

    private record Exclusion(UUID by, OffsetDateTime at, OffsetDateTime addedBefore) {}

    /** What the difference gives: the visible work packages (newest first) and the commits with no link at all. */
    private record Difference(LinkedHashMap<UUID, Candidate> candidates, List<Unlinked> unlinked) {}

    private static final String ENVIRONMENTS = """
            select id, name, position, production, hidden,
                   array(select r.id from cicd_repositories r where r.project_id = ?::uuid and r.detached_at is null order by r.id) as repositories
              from cicd_environments where project_id = ?::uuid order by position, name""";

    /**
     * The repositories attached to P now and the baseline of each: the first successful deployment to T (보완 3). A commit that
     * came into S before it is taken as already out. Parameters: P, T.
     */
    private static final String REPOS_AND_BASELINE = """
            repos as (select id from cicd_repositories where project_id = ?::uuid and detached_at is null),
                 baseline as (select repository_id, min(deployed_at) as at from cicd_deployments
                               where environment_id = ?::uuid and status = 'success' and repository_id in (select id from repos)
                               group by repository_id)""";

    /**
     * S − T per attached repository after the baseline, each commit with the work packages it is linked to (any project: a commit
     * linked only outside the tree is not "unlinked" either, preflight F12) and those of them in P's tree. Parameters: P, P, T, S, T.
     */
    private static final String DIFFERENCE = """
            with recursive tree as (select id from projects where id = ?::uuid union select p.id from projects p join tree t on p.parent_id = t.id),
                 """ + REPOS_AND_BASELINE + """
            ,
                 diff as (select s.commit_id, s.repository_id, s.added_at, s.deployment_id from cicd_environment_commits s
                           where s.environment_id = ?::uuid and s.repository_id in (select id from repos)
                             and not exists (select 1 from cicd_environment_commits t where t.environment_id = ?::uuid and t.commit_id = s.commit_id)
                             and not exists (select 1 from baseline b where b.repository_id = s.repository_id and s.added_at < b.at)),
                 wl as (""" + CicdVerificationStates.workPackageCommits("c.id in (select commit_id from diff)") + """
            )
            select d.commit_id, d.added_at, dep.deployed_at, dep.web_url, c.sha, c.title, c.web_url as commit_url, r.full_path,
                   wl.wp as linked, w.id as wp, w.project_id, w.number, w.subject, w.status_id, w.version_id
              from diff d join cicd_commits c on c.id = d.commit_id join cicd_repositories r on r.id = d.repository_id
              join cicd_deployments dep on dep.id = d.deployment_id
              left join wl on wl.commit_id = d.commit_id
              left join work_packages w on w.id = wl.wp and w.project_id in (select id from tree)
             order by d.added_at desc, c.sha""";

    private static final String EXCLUSIONS = """
            select work_package_id, excluded_by, excluded_at, added_before from cicd_board_exclusions
             where environment_id = ?::uuid and target_environment_id = ?::uuid and work_package_id = any (?::uuid[])""";

    /** Open work packages of the version, in any project (a shared version); visibility is checked afterwards. */
    private static final String PLANNED = """
            select w.id, w.number, w.subject, w.status_id, w.project_id from work_packages w join statuses s on s.id = w.status_id
             where w.version_id = ?::uuid and not s.is_closed order by w.number""";

    /**
     * For planned work packages: whether any link is left (commit or merge request), how many of their commits are in P's attached
     * repositories, and how many of those are out already: in T, or in S before the baseline (preflight F14, as the board counts
     * them). Parameters: P, T, ids, ids, T, S, ids.
     */
    private static final String PLANNED_COMMITS = "with " + REPOS_AND_BASELINE + ",\n     k as ("
            + CicdVerificationStates.workPackageCommits("l.work_package_id = any (?::uuid[])") + """
            )
            select w.id,
                   exists (select 1 from work_package_cicd_links l where l.work_package_id = w.id and l.dismissed_at is null) as linked,
                   count(c.id) as commits,
                   count(c.id) filter (where exists (select 1 from cicd_environment_commits t where t.environment_id = ?::uuid and t.commit_id = c.id)
                                          or exists (select 1 from cicd_environment_commits s join baseline b on b.repository_id = s.repository_id
                                                      where s.environment_id = ?::uuid and s.commit_id = c.id and s.added_at < b.at)) as shipped
              from unnest(?::uuid[]) as w(id)
              left join k on k.wp = w.id
              left join cicd_commits c on c.id = k.commit_id and c.repository_id in (select id from repos)
             group by w.id""";

    private final DSLContext db;
    private final CicdVerificationStates states;
    private final CicdEnvironmentSignals signals;
    private final Assignees assignees;

    public CicdReleaseBoard(DSLContext db, CicdVerificationStates states, CicdEnvironmentSignals signals, Assignees assignees) {
        this.db = db;
        this.states = states;
        this.signals = signals;
        this.assignees = assignees;
    }

    /** The source and target, defaults filled in; both null when there is no default. 404 for another project's environment, 422 for the same one twice. */
    public Pair pair(ProjectRow p, UUID source, UUID target) {
        return pair(setup(p).environments(), source, target);
    }

    /** The work packages on the board (visible, not excluded) and when their commits last came into S. Empty without a pair. */
    public Map<UUID, OffsetDateTime> listed(Ctx ctx, ProjectRow p, UUID source, UUID target) {
        var setup = setup(p);
        var pair = pair(setup.environments(), source, target);
        if (pair.source() == null) return new HashMap<>();
        var candidates = difference(ctx, p, pair).candidates().keySet();
        var latest = states.latestIn(pair.source(), candidates);
        var excluded = validExclusions(pair, latest);
        var out = new HashMap<UUID, OffsetDateTime>();
        for (var id : candidates) if (!excluded.containsKey(id)) out.put(id, latest.get(id));
        return out;
    }

    public Board board(Ctx ctx, ProjectRow p, UUID source, UUID target, String version) {
        var s = ctx.s();
        var setup = setup(p);
        var pair = pair(setup.environments(), source, target);
        var versions = s.versions().stream().filter(v -> v.projectId().equals(p.id()))
                .sorted(Comparator.comparing((Snapshot.VersionRow v) -> v.endDate() == null ? LocalDate.MAX : v.endDate()).thenComparing(Snapshot.VersionRow::name))
                .toList();
        var versionId = version(ctx, versions, version);
        var environments = setup.environments().stream().map(e -> new EnvironmentRef(e.id(), e.name(), e.position(), e.production(), e.hidden())).toList();
        var versionRefs = versions.stream().map(v -> new VersionRef(v.id(), v.name(), v.status(), v.endDate())).toList();
        boolean canVerify = ctx.can(p, "release.verify");
        if (pair.source() == null)
            return new Board(environments, null, null, versionRefs, versionId, List.of(), List.of(), 0, 0, List.of(), List.of(), List.of(), canVerify);

        var names = new HashMap<UUID, String>();
        setup.environments().forEach(e -> names.put(e.id(), e.name()));
        var diff = difference(ctx, p, pair);
        var candidates = diff.candidates();
        var latest = states.latestIn(pair.source(), candidates.keySet());
        var exclusions = validExclusions(pair, latest);
        var shown = candidates.values().stream().filter(c -> !exclusions.containsKey(c.id())).toList();
        var shownIds = shown.stream().map(Candidate::id).toList();
        var current = states.current(pair.source(), shownIds);
        var people = assignees.of(shownIds);

        var cards = new ArrayList<Card>();
        int interloperCount = 0;
        for (var c : shown) {
            var now = current.get(c.id());
            boolean interloper = versionId != null && !versionId.equals(c.versionId());
            if (interloper) interloperCount++;
            var last = now == null ? null
                    : new VerificationDto(now.id(), now.result(), now.reason(), Refs.user(s, now.verifiedBy()), now.verifiedAt(), names.get(pair.source()));
            cards.add(new Card(c.id(), c.number(), c.subject(), Refs.status(s, c.statusId()),
                    people.getOrDefault(c.id(), List.of()).stream().map(a -> Refs.user(s, a.userId())).toList(), Refs.project(s, c.projectId()),
                    CicdVerificationStates.column(now, latest.get(c.id())), c.deployedAt(), c.deploymentUrl(), last, interloper));
        }
        cards.sort(Comparator.comparingInt((Card c) -> COLUMNS.indexOf(c.column())).thenComparingLong(Card::number));

        var excluded = candidates.values().stream().filter(c -> exclusions.containsKey(c.id()))
                .map(c -> new Excluded(c.id(), c.number(), c.subject(), Refs.user(s, exclusions.get(c.id()).by()), exclusions.get(c.id()).at()))
                .sorted(Comparator.comparing((Excluded e) -> e.at().toInstant()).reversed().thenComparingLong(Excluded::number)).toList();

        var missing = versionId == null ? List.<Missing>of() : missing(ctx, p, pair, versionId, candidates.keySet());
        var unlinked = diff.unlinked().stream().limit(UNLINKED_LIMIT)
                .map(u -> new UnlinkedCommit(u.repository(), u.sha().substring(0, Math.min(7, u.sha().length())), u.title(), u.webUrl(), u.deployedAt())).toList();
        return new Board(environments, pair.source(), pair.target(), versionRefs, versionId, cards, missing, interloperCount, diff.unlinked().size(),
                unlinked, excluded, notices(pair, names, setup.repositories()), canVerify);
    }

    /* ---------- environments and version ---------- */

    private Setup setup(ProjectRow p) {
        var rows = db.fetch(ENVIRONMENTS, p.id(), p.id());
        var envs = rows.map(r -> new Env(r.get(0, UUID.class), r.get(1, String.class), r.get(2, Integer.class), r.get(3, Boolean.class), r.get(4, Boolean.class)));
        List<UUID> repositories = rows.isEmpty() ? List.of() : Arrays.asList(rows.get(0).get(5, UUID[].class));
        return new Setup(envs, repositories);
    }

    private static Pair pair(List<Env> environments, UUID source, UUID target) {
        var ids = new HashSet<UUID>();
        environments.forEach(e -> ids.add(e.id()));
        if ((source != null && !ids.contains(source)) || (target != null && !ids.contains(target)))
            throw ApiException.notFound("환경을 찾을 수 없습니다.");
        var rows = environments.stream().map(e -> new CicdVerificationStates.EnvironmentRow(e.id(), e.position(), e.production(), e.hidden())).toList();
        if (target == null) target = CicdVerificationStates.defaultTarget(rows);
        if (source == null && target != null) source = CicdVerificationStates.defaultSource(rows, target);
        if (source == null || target == null) return new Pair(null, null);
        if (source.equals(target)) throw ApiException.unprocessable("비교 원본과 대상은 다른 환경이어야 합니다.", null);
        return new Pair(source, target);
    }

    /**
     * The chosen version: an id of this project's versions, "none" (no version), or by default (보완 7) the open version ending
     * soonest from today, else the one that ended last, else the first by name without an end date, else none.
     */
    private static UUID version(Ctx ctx, List<Snapshot.VersionRow> versions, String asked) {
        if ("none".equals(asked)) return null;
        if (asked != null) {
            UUID id;
            try {
                id = UUID.fromString(asked);
            } catch (IllegalArgumentException e) {
                throw ApiException.notFound("버전을 찾을 수 없습니다.");
            }
            if (versions.stream().noneMatch(v -> v.id().equals(id))) throw ApiException.notFound("버전을 찾을 수 없습니다.");
            return id;
        }
        var today = ctx.today();
        var open = versions.stream().filter(v -> "open".equals(v.status())).toList();
        var byName = Comparator.comparing(Snapshot.VersionRow::name);
        return open.stream().filter(v -> v.endDate() != null && !v.endDate().isBefore(today))
                .min(Comparator.comparing(Snapshot.VersionRow::endDate).thenComparing(byName))
                .or(() -> open.stream().filter(v -> v.endDate() != null).max(Comparator.comparing(Snapshot.VersionRow::endDate).thenComparing(byName.reversed())))
                .or(() -> open.stream().filter(v -> v.endDate() == null).min(byName))
                .map(Snapshot.VersionRow::id).orElse(null);
    }

    /* ---------- the difference ---------- */

    private static boolean visible(Ctx ctx, UUID projectId, UUID wpId) {
        var project = ctx.s().project(projectId).orElse(null);
        return project != null && ctx.can(project, wpId, "wp.view");
    }

    private Difference difference(Ctx ctx, ProjectRow p, Pair pair) {
        var rows = db.fetch(DIFFERENCE, p.id(), p.id(), pair.target(), pair.source(), pair.target());
        var candidates = new LinkedHashMap<UUID, Candidate>();
        var linked = new HashSet<UUID>();
        var commits = new LinkedHashMap<UUID, Unlinked>();
        var hidden = new HashSet<UUID>();
        for (var r : rows) {
            var commitId = r.get(0, UUID.class);
            commits.putIfAbsent(commitId, new Unlinked(r.get(7, String.class), r.get(4, String.class), r.get(5, String.class), r.get(6, String.class),
                    r.get(2, OffsetDateTime.class)));
            if (r.get(8, UUID.class) != null) linked.add(commitId);
            var wp = r.get(9, UUID.class);
            if (wp == null || candidates.containsKey(wp) || hidden.contains(wp)) continue;   // rows are newest first: a work package's first row is its card's
            var projectId = r.get(10, UUID.class);
            if (!visible(ctx, projectId, wp)) {
                hidden.add(wp);
                continue;
            }
            candidates.put(wp, new Candidate(wp, r.get(11, Long.class), r.get(12, String.class), r.get(13, UUID.class), r.get(14, UUID.class), projectId,
                    r.get(2, OffsetDateTime.class), r.get(3, String.class)));
        }
        var unlinked = commits.entrySet().stream().filter(e -> !linked.contains(e.getKey())).map(Map.Entry::getValue).toList();
        return new Difference(candidates, unlinked);
    }

    /** Exclusions of this board that still hold: no commit of the work package came into S after it (added_before). */
    private Map<UUID, Exclusion> validExclusions(Pair pair, Map<UUID, OffsetDateTime> latest) {
        var out = new HashMap<UUID, Exclusion>();
        if (latest.isEmpty()) return out;
        for (var r : db.fetch(EXCLUSIONS, pair.source(), pair.target(), latest.keySet().toArray(UUID[]::new))) {
            var wp = r.get(0, UUID.class);
            var addedBefore = r.get(3, OffsetDateTime.class);
            var in = latest.get(wp);
            if (in != null && !in.toInstant().isAfter(addedBefore.toInstant()))
                out.put(wp, new Exclusion(r.get(1, UUID.class), r.get(2, OffsetDateTime.class), addedBefore));
        }
        return out;
    }

    /* ---------- version check and banner ---------- */

    /**
     * Planned work packages (open, of the version, visible) that are neither on the board nor excluded from it, and not out
     * already: every commit (in P's attached repositories) in T, or in S before the baseline. A work package without such
     * commits stays missing. onBoard: the visible work packages of the difference (cards and valid exclusions).
     */
    private List<Missing> missing(Ctx ctx, ProjectRow p, Pair pair, UUID versionId, Set<UUID> onBoard) {
        var s = ctx.s();
        var planned = new LinkedHashMap<UUID, org.jooq.Record>();
        for (var r : db.fetch(PLANNED, versionId)) {
            var id = r.get(0, UUID.class);
            if (onBoard.contains(id) || !visible(ctx, r.get(4, UUID.class), id)) continue;
            planned.put(id, r);
        }
        if (planned.isEmpty()) return List.of();
        var ids = planned.keySet().toArray(UUID[]::new);
        var developing = new HashSet<UUID>();
        var out = new LinkedHashSet<UUID>();
        for (var r : db.fetch(PLANNED_COMMITS, p.id(), pair.target(), ids, ids, pair.target(), pair.source(), ids)) {
            var id = r.get(0, UUID.class);
            long commits = r.get(2, Long.class);
            long shipped = r.get(3, Long.class);
            if (commits > 0 && shipped == commits) continue;
            out.add(id);
            if (Boolean.TRUE.equals(r.get(1, Boolean.class))) developing.add(id);
        }
        var list = new ArrayList<Missing>();
        for (var id : out) {
            var r = planned.get(id);
            list.add(new Missing(id, r.get(1, Long.class), r.get(2, String.class), Refs.status(s, r.get(3, UUID.class)),
                    developing.contains(id) ? "developing" : "no_code"));
        }
        list.sort(Comparator.comparing((Missing m) -> "developing".equals(m.group()) ? 0 : 1).thenComparingLong(Missing::number));
        return list;
    }

    /** S first, then T; for each: checking, failed, first_cutoff, partial (the development tab's judgment, CicdEnvironmentSignals). */
    private List<Notice> notices(Pair pair, Map<UUID, String> names, List<UUID> repositories) {
        if (repositories.isEmpty()) return List.of();
        var envs = List.of(pair.source(), pair.target());
        var rows = signals.rows(envs, repositories);
        var partial = signals.partial(envs, repositories);
        var repoSet = Set.copyOf(repositories);
        var out = new ArrayList<Notice>();
        for (var env : envs) {
            var sig = CicdEnvironmentSignals.summarize(rows, env, repoSet);
            var name = names.get(env);
            if (sig.checking()) out.add(new Notice(env, name, "checking"));
            if (sig.failed()) out.add(new Notice(env, name, "failed"));
            if (!sig.firstCutoffs().isEmpty()) out.add(new Notice(env, name, "first_cutoff"));
            if (partial.contains(env)) out.add(new Notice(env, name, "partial"));
        }
        return out;
    }
}
