package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_COMMITS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_HEADS;
import static com.openpms.db.Tables.CICD_MERGE_REQUESTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGE_CICD_LINKS;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.core.Current;
import com.openpms.wp.WorkPackageService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The work package's "개발" tab: linked merge requests and commits, a manual link by MR/PR address, and dismissing a link.
 * The work package must be visible and the person needs cicd.view (module on), otherwise the answer is the same 404 as for
 * an invisible work package; changing needs cicd.link. A manual link only accepts merge requests that were already received
 * for a repository attached to the work package's project or one of its ancestors (a repository covers its project and the sub-projects); nothing is fetched remotely and the answer
 * for anything else is one message that tells nothing about other projects.
 */
@RestController
@RequestMapping("/api/v1/work-packages/{number}/cicd")
public class DevTabController {
    static final int COMMIT_LIMIT = 50;
    private static final String NOT_FOUND = "작업을 찾을 수 없습니다.";

    /** One badge for a commit or a branch: the state of its workflows together, and the newest build that made that state. */
    public record BuildDto(String status, String webUrl, OffsetDateTime at, String source, int count) {}

    public record MrDto(UUID linkId, String repository, long iid, String title, String state, String sourceBranch, String targetBranch,
                        String author, Dto.UserRef user, OffsetDateTime mergedAt, OffsetDateTime updatedAt, String webUrl, String source,
                        BuildDto build, boolean detached) {
        public MrDto(UUID linkId, String repository, long iid, String title, String state, String sourceBranch, String targetBranch,
                     String author, Dto.UserRef user, OffsetDateTime mergedAt, OffsetDateTime updatedAt, String webUrl, String source) {
            this(linkId, repository, iid, title, state, sourceBranch, targetBranch, author, user, mergedAt, updatedAt, webUrl, source, null, false);
        }
    }

    public record CommitDto(UUID linkId, String repository, String shortSha, String title, String author, Dto.UserRef user,
                            OffsetDateTime committedAt, String webUrl, String source, BuildDto build, List<String> environments, boolean detached) {
        public CommitDto(UUID linkId, String repository, String shortSha, String title, String author, Dto.UserRef user,
                         OffsetDateTime committedAt, String webUrl, String source) {
            this(linkId, repository, shortSha, title, author, user, committedAt, webUrl, source, null, List.of(), false);
        }
    }

    /**
     * How far the work went in one environment. state: in, partial, checking, failed, before_first, none. included of total linked
     * commits are in; partialCheck: some of those were added by a check that saw only part of the history; checking: a newer
     * deployment of the environment is still being compared (state is in when every linked commit is already there, else checking).
     */
    public record EnvironmentStatus(UUID id, String name, boolean production, String state, int included, int total, boolean partialCheck,
                                    boolean checking, OffsetDateTime deployedAt, String webUrl, String project) {}

    public record DevInfo(List<MrDto> mergeRequests, List<CommitDto> commits, boolean canLink, List<EnvironmentStatus> environments) {
        public DevInfo(List<MrDto> mergeRequests, List<CommitDto> commits, boolean canLink) {
            this(mergeRequests, commits, canLink, List.of());
        }
    }

    public record LinkIn(String url) {}

    private final Current current;
    private final WorkPackageService workPackages;
    private final CicdLinker linker;
    private final DSLContext db;

    /**
     * The commits this work package has: commit links and the merge commit of linked merged merge requests, only in repositories
     * attached to this work package's project or an ancestor (the repository's current project tree).
     */
    private static final String LINKED = """
            with k(id) as (
                   select l.commit_id from work_package_cicd_links l
                    where l.work_package_id = ?::uuid and l.dismissed_at is null and l.commit_id is not null
                   union
                   select c.id from work_package_cicd_links l join cicd_merge_requests m on m.id = l.merge_request_id
                     join cicd_commits c on c.repository_id = m.repository_id and c.sha = m.merge_commit_sha
                    where l.work_package_id = ?::uuid and l.dismissed_at is null and m.state = 'merged' and m.merge_commit_sha is not null)
            select c.id, c.repository_id, c.committed_at, r.project_id, p.name
              from k join cicd_commits c on c.id = k.id join cicd_repositories r on r.id = c.repository_id
              join projects p on p.id = r.project_id
             where r.project_id = any (?::uuid[])""";

    /** The newest build of each workflow name per commit sha (or branch name): one row each. %1$s is sha or ref. */
    private static final String BUILDS = """
            select distinct on (repository_id, %1$s, name) repository_id, %1$s, name, status, web_url, finished_at, updated_at, source
              from cicd_pipelines
             where (repository_id, %1$s) in (select * from unnest(?::uuid[], ?::text[]))
             order by repository_id, %1$s, name, updated_at desc""";

    public DevTabController(Current current, WorkPackageService workPackages, CicdLinker linker, DSLContext db) {
        this.current = current;
        this.workPackages = workPackages;
        this.linker = linker;
        this.db = db;
    }

    private WorkPackageService.Found viewable(long number) {
        var ctx = current.ctx();
        var found = workPackages.find(ctx, number);
        if (!ctx.can(found.project(), "cicd.view")) throw ApiException.notFound(NOT_FOUND);
        return found;
    }

    private WorkPackageService.Found linkable(long number) {
        var found = viewable(number);
        current.ctx().require(found.project(), "cicd.link", "개발 연결을 고칠 권한이 없습니다.");
        return found;
    }

    @GetMapping
    public DevInfo get(@PathVariable long number) {
        var found = viewable(number);
        var wpId = found.wp().getId();
        var mrRows = mrRows(WORK_PACKAGE_CICD_LINKS.WORK_PACKAGE_ID.eq(wpId).and(WORK_PACKAGE_CICD_LINKS.DISMISSED_AT.isNull()));
        var commitRows = db.select(WORK_PACKAGE_CICD_LINKS.ID, CICD_COMMITS.ID, CICD_REPOSITORIES.ID, CICD_REPOSITORIES.DETACHED_AT, CICD_REPOSITORIES.FULL_PATH, CICD_COMMITS.SHA, CICD_COMMITS.TITLE,
                        CICD_COMMITS.AUTHOR_NAME, CICD_COMMITS.USER_ID, CICD_COMMITS.COMMITTED_AT, CICD_COMMITS.WEB_URL, WORK_PACKAGE_CICD_LINKS.SOURCE)
                .from(WORK_PACKAGE_CICD_LINKS).join(CICD_COMMITS).on(CICD_COMMITS.ID.eq(WORK_PACKAGE_CICD_LINKS.COMMIT_ID))
                .join(CICD_REPOSITORIES).on(CICD_REPOSITORIES.ID.eq(CICD_COMMITS.REPOSITORY_ID))
                .where(WORK_PACKAGE_CICD_LINKS.WORK_PACKAGE_ID.eq(wpId), WORK_PACKAGE_CICD_LINKS.DISMISSED_AT.isNull())
                .orderBy(CICD_COMMITS.COMMITTED_AT.desc(), CICD_COMMITS.ID).limit(COMMIT_LIMIT).fetch();
        var ids = new HashSet<UUID>();
        mrRows.forEach(r -> ids.add(r.get(CICD_MERGE_REQUESTS.USER_ID)));
        commitRows.forEach(r -> ids.add(r.get(CICD_COMMITS.USER_ID)));
        ids.remove(null);
        var users = users(ids);
        var mrBuilds = builds("ref", mrRows.stream().map(r -> key(r.get(CICD_REPOSITORIES.ID), r.get(CICD_MERGE_REQUESTS.SOURCE_BRANCH))).toList());
        var commitBuilds = builds("sha", commitRows.stream().map(r -> key(r.get(CICD_REPOSITORIES.ID), r.get(CICD_COMMITS.SHA))).toList());
        var mrs = mrRows.stream().map(r -> mrDto(r, users, mrBuilds)).toList();
        var row = environments(visibleAncestors(found.project().id()), wpId, commitRows.stream().map(r -> r.get(CICD_COMMITS.ID)).toList());
        var commits = commitRows.stream().map(r -> new CommitDto(r.get(WORK_PACKAGE_CICD_LINKS.ID), r.get(CICD_REPOSITORIES.FULL_PATH),
                r.get(CICD_COMMITS.SHA).substring(0, Math.min(7, r.get(CICD_COMMITS.SHA).length())), r.get(CICD_COMMITS.TITLE),
                r.get(CICD_COMMITS.AUTHOR_NAME), users.get(r.get(CICD_COMMITS.USER_ID)), r.get(CICD_COMMITS.COMMITTED_AT),
                r.get(CICD_COMMITS.WEB_URL), r.get(WORK_PACKAGE_CICD_LINKS.SOURCE),
                commitBuilds.get(key(r.get(CICD_REPOSITORIES.ID), r.get(CICD_COMMITS.SHA))),
                row.commitEnvironments().getOrDefault(r.get(CICD_COMMITS.ID), List.of()), r.get(CICD_REPOSITORIES.DETACHED_AT) != null)).toList();
        return new DevInfo(mrs, commits, current.ctx().can(found.project(), "cicd.link"), row.environments());
    }

    @PostMapping("/links")
    public ResponseEntity<MrDto> link(@PathVariable long number, @RequestBody LinkIn in) {
        var found = linkable(number);
        var wpId = found.wp().getId();
        var url = normalize(in == null ? null : in.url());
        // only the ancestors whose repositories this person may see: a member of a private child is not shown the parent's MRs
        var tree = visibleAncestors(found.project().id());
        var mrId = url.isEmpty() || tree.isEmpty() ? null : findMergeRequest(tree, url);
        if (mrId == null) throw ApiException.unprocessable("이 프로젝트에 붙은 저장소의 MR/PR 주소가 아니거나 아직 받지 않은 MR/PR입니다.", null);
        var me = current.ctx().userId();
        // a dismissed link is revived (source becomes 'manual': the person chose it); an active one stays as it is
        int revived = db.update(WORK_PACKAGE_CICD_LINKS).set(WORK_PACKAGE_CICD_LINKS.DISMISSED_AT, (OffsetDateTime) null)
                .set(WORK_PACKAGE_CICD_LINKS.DISMISSED_BY, (UUID) null).set(WORK_PACKAGE_CICD_LINKS.SOURCE, "manual")
                .set(WORK_PACKAGE_CICD_LINKS.CREATED_BY, me)
                .where(WORK_PACKAGE_CICD_LINKS.WORK_PACKAGE_ID.eq(wpId), WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID.eq(mrId),
                        WORK_PACKAGE_CICD_LINKS.DISMISSED_AT.isNotNull()).execute();
        if (revived == 0) {
            db.insertInto(WORK_PACKAGE_CICD_LINKS).set(WORK_PACKAGE_CICD_LINKS.WORK_PACKAGE_ID, wpId)
                    .set(WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID, mrId).set(WORK_PACKAGE_CICD_LINKS.SOURCE, "manual")
                    .set(WORK_PACKAGE_CICD_LINKS.CREATED_BY, me).onConflictDoNothing().execute();
        }
        var row = mrRows(WORK_PACKAGE_CICD_LINKS.WORK_PACKAGE_ID.eq(wpId).and(WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID.eq(mrId))).get(0);
        var ids = new HashSet<UUID>();
        if (row.get(CICD_MERGE_REQUESTS.USER_ID) != null) ids.add(row.get(CICD_MERGE_REQUESTS.USER_ID));
        var builds = builds("ref", java.util.Collections.singletonList(key(row.get(CICD_REPOSITORIES.ID), row.get(CICD_MERGE_REQUESTS.SOURCE_BRANCH))));
        return ResponseEntity.status(HttpStatus.CREATED).body(mrDto(row, users(ids), builds));
    }

    @DeleteMapping("/links/{linkId}")
    public ResponseEntity<Void> dismiss(@PathVariable long number, @PathVariable UUID linkId) {
        var found = linkable(number);
        int n = db.update(WORK_PACKAGE_CICD_LINKS).set(WORK_PACKAGE_CICD_LINKS.DISMISSED_AT, OffsetDateTime.now())
                .set(WORK_PACKAGE_CICD_LINKS.DISMISSED_BY, current.ctx().userId())
                .where(WORK_PACKAGE_CICD_LINKS.ID.eq(linkId), WORK_PACKAGE_CICD_LINKS.WORK_PACKAGE_ID.eq(found.wp().getId()),
                        WORK_PACKAGE_CICD_LINKS.DISMISSED_AT.isNull()).execute();
        if (n == 0) throw ApiException.notFound("연결을 찾을 수 없습니다.");
        return ResponseEntity.noContent().build();
    }

    private List<Record> mrRows(Condition where) {
        return db.select(WORK_PACKAGE_CICD_LINKS.ID, CICD_REPOSITORIES.ID, CICD_REPOSITORIES.DETACHED_AT, CICD_REPOSITORIES.FULL_PATH, CICD_MERGE_REQUESTS.IID, CICD_MERGE_REQUESTS.TITLE,
                        CICD_MERGE_REQUESTS.STATE, CICD_MERGE_REQUESTS.SOURCE_BRANCH, CICD_MERGE_REQUESTS.TARGET_BRANCH,
                        CICD_MERGE_REQUESTS.AUTHOR_NAME, CICD_MERGE_REQUESTS.USER_ID, CICD_MERGE_REQUESTS.MERGED_AT,
                        CICD_MERGE_REQUESTS.UPDATED_AT, CICD_MERGE_REQUESTS.WEB_URL, WORK_PACKAGE_CICD_LINKS.SOURCE)
                .from(WORK_PACKAGE_CICD_LINKS).join(CICD_MERGE_REQUESTS).on(CICD_MERGE_REQUESTS.ID.eq(WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID))
                .join(CICD_REPOSITORIES).on(CICD_REPOSITORIES.ID.eq(CICD_MERGE_REQUESTS.REPOSITORY_ID))
                .where(where).orderBy(CICD_MERGE_REQUESTS.UPDATED_AT.desc(), CICD_MERGE_REQUESTS.ID).fetch().map(r -> (Record) r);
    }

    private static MrDto mrDto(Record r, Map<UUID, Dto.UserRef> users, Map<String, BuildDto> builds) {
        return new MrDto(r.get(WORK_PACKAGE_CICD_LINKS.ID), r.get(CICD_REPOSITORIES.FULL_PATH), r.get(CICD_MERGE_REQUESTS.IID),
                r.get(CICD_MERGE_REQUESTS.TITLE), r.get(CICD_MERGE_REQUESTS.STATE), r.get(CICD_MERGE_REQUESTS.SOURCE_BRANCH),
                r.get(CICD_MERGE_REQUESTS.TARGET_BRANCH), r.get(CICD_MERGE_REQUESTS.AUTHOR_NAME), users.get(r.get(CICD_MERGE_REQUESTS.USER_ID)),
                r.get(CICD_MERGE_REQUESTS.MERGED_AT), r.get(CICD_MERGE_REQUESTS.UPDATED_AT), r.get(CICD_MERGE_REQUESTS.WEB_URL),
                r.get(WORK_PACKAGE_CICD_LINKS.SOURCE), builds.get(key(r.get(CICD_REPOSITORIES.ID), r.get(CICD_MERGE_REQUESTS.SOURCE_BRANCH))),
                r.get(CICD_REPOSITORIES.DETACHED_AT) != null);
    }

    /** The project and its ancestors whose CI/CD this person may see (a member of a private child is not shown the parent's). */
    private List<UUID> visibleAncestors(UUID projectId) {
        var ctx = current.ctx();
        return linker.projectAncestors(projectId).stream().filter(id -> {
            var p = ctx.s().projects().get(id);
            return p != null && ctx.can(p, "cicd.view");
        }).toList();
    }

    /** null for a missing or empty sha/branch (nothing to look up); a branch literally named "null" is a name like any other. */
    private static String key(UUID repository, String sha) {
        return sha == null || sha.isEmpty() ? null : repository + "|" + sha;
    }

    /** Badges for (repository, sha-or-ref) keys in one query. column: "sha" or "ref". Keys with no builds are absent. */
    private Map<String, BuildDto> builds(String column, List<String> keys) {
        var wanted = keys.stream().filter(k -> k != null).distinct().toList();
        if (wanted.isEmpty()) return new HashMap<>();
        var repositories = wanted.stream().map(k -> UUID.fromString(k.substring(0, k.indexOf('|')))).toArray(UUID[]::new);
        var names = wanted.stream().map(k -> k.substring(k.indexOf('|') + 1)).toArray(String[]::new);
        var rows = db.fetch(BUILDS.formatted(column), repositories, names);
        var byKey = new HashMap<String, List<Record>>();
        for (var r : rows) byKey.computeIfAbsent(key(r.get(0, UUID.class), r.get(1, String.class)), x -> new ArrayList<>()).add(r);
        var out = new HashMap<String, BuildDto>();
        byKey.forEach((k, list) -> {
            var status = CicdStates.summarize(list.stream().map(r -> r.get(3, String.class)).toList());
            var newest = list.stream().filter(r -> status.equals(r.get(3, String.class)))
                    .max(Comparator.comparing(r -> r.get(6, OffsetDateTime.class).toInstant())).orElseThrow();
            var finished = newest.get(5, OffsetDateTime.class);
            out.put(k, new BuildDto(status, newest.get(4, String.class), finished != null ? finished : newest.get(6, OffsetDateTime.class),
                    newest.get(7, String.class), list.size()));
        });
        return out;
    }

    private record Row(List<EnvironmentStatus> environments, Map<UUID, List<String>> commitEnvironments) {}

    private record Linked(UUID commitId, UUID repositoryId, OffsetDateTime committedAt, UUID projectId, String projectName) {}

    private record EnvInfo(UUID id, String name, UUID projectId, int position, boolean production) {}

    /**
     * The environment row: for each project that has linked commits, its visible environments in order, with how many of those
     * commits are in. A fixed number of queries however many commits there are. shown: the commits of the list (their environments).
     */
    private Row environments(List<UUID> visibleProjects, UUID workPackage, List<UUID> shown) {
        var linked = db.fetch(LINKED, workPackage, workPackage, visibleProjects.toArray(UUID[]::new)).stream()
                .map(r -> new Linked(r.get(0, UUID.class), r.get(1, UUID.class), r.get(2, OffsetDateTime.class), r.get(3, UUID.class), r.get(4, String.class))).toList();
        if (linked.isEmpty()) return new Row(List.of(), Map.of());
        var projects = new HashMap<UUID, String>();
        linked.forEach(l -> projects.put(l.projectId(), l.projectName()));
        var envs = db.select(CICD_ENVIRONMENTS.ID, CICD_ENVIRONMENTS.NAME, CICD_ENVIRONMENTS.PROJECT_ID, CICD_ENVIRONMENTS.POSITION, CICD_ENVIRONMENTS.PRODUCTION)
                .from(CICD_ENVIRONMENTS).where(CICD_ENVIRONMENTS.PROJECT_ID.in(projects.keySet()), CICD_ENVIRONMENTS.HIDDEN.isFalse()).fetch().stream()
                .map(r -> new EnvInfo(r.value1(), r.value2(), r.value3(), r.value4(), r.value5())).toList();
        if (envs.isEmpty()) return new Row(List.of(), Map.of());
        var envIds = envs.stream().map(EnvInfo::id).toList();
        var commitIds = linked.stream().map(Linked::commitId).toList();
        var repositoryIds = linked.stream().map(Linked::repositoryId).distinct().toList();
        var inRows = db.select(CICD_ENVIRONMENT_COMMITS.ENVIRONMENT_ID, CICD_ENVIRONMENT_COMMITS.COMMIT_ID, CICD_DEPLOYMENTS.DEPLOYED_AT,
                        CICD_DEPLOYMENTS.WEB_URL, CICD_DEPLOYMENTS.INCLUSION)
                .from(CICD_ENVIRONMENT_COMMITS).join(CICD_DEPLOYMENTS).on(CICD_DEPLOYMENTS.ID.eq(CICD_ENVIRONMENT_COMMITS.DEPLOYMENT_ID))
                .where(CICD_ENVIRONMENT_COMMITS.COMMIT_ID.in(commitIds), CICD_ENVIRONMENT_COMMITS.ENVIRONMENT_ID.in(envIds)).fetch();
        var waiting = db.select(CICD_DEPLOYMENTS.ENVIRONMENT_ID, CICD_DEPLOYMENTS.REPOSITORY_ID, CICD_DEPLOYMENTS.INCLUSION, CICD_DEPLOYMENTS.FIRST_CUTOFF_AT)
                .from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ENVIRONMENT_ID.in(envIds), CICD_DEPLOYMENTS.REPOSITORY_ID.in(repositoryIds),
                        CICD_DEPLOYMENTS.INCLUSION.eq("checking")
                                .or(CICD_DEPLOYMENTS.INCLUSION.eq("first").and(CICD_DEPLOYMENTS.FIRST_CUTOFF_AT.isNotNull()))
                                .or(CICD_DEPLOYMENTS.INCLUSION.eq("failed").and(DSL.exists(DSL.selectOne().from(CICD_ENVIRONMENT_HEADS)
                                        .where(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID.eq(CICD_DEPLOYMENTS.ID))))))
                .fetch().stream().map(r -> (Record) r).toList();
        var commitInfo = new HashMap<UUID, Linked>();
        linked.forEach(l -> commitInfo.put(l.commitId(), l));
        var envById = new HashMap<UUID, EnvInfo>();
        envs.forEach(e -> envById.put(e.id(), e));

        // included commits per environment (only environments of the commit's own current project)
        var includedIn = new HashMap<UUID, Set<UUID>>();
        var latest = new HashMap<UUID, Record>();
        var partialCheck = new HashSet<UUID>();
        var commitEnvs = new HashMap<UUID, List<EnvInfo>>();
        for (var r : inRows) {
            var env = envById.get(r.get(CICD_ENVIRONMENT_COMMITS.ENVIRONMENT_ID));
            var commit = commitInfo.get(r.get(CICD_ENVIRONMENT_COMMITS.COMMIT_ID));
            if (env == null || commit == null || !env.projectId().equals(commit.projectId())) continue;
            includedIn.computeIfAbsent(env.id(), x -> new HashSet<>()).add(commit.commitId());
            commitEnvs.computeIfAbsent(commit.commitId(), x -> new ArrayList<>()).add(env);
            if ("partial".equals(r.get(CICD_DEPLOYMENTS.INCLUSION))) partialCheck.add(env.id());
            var old = latest.get(env.id());
            if (old == null || r.get(CICD_DEPLOYMENTS.DEPLOYED_AT).isAfter(old.get(CICD_DEPLOYMENTS.DEPLOYED_AT))) latest.put(env.id(), r);
        }

        var out = new ArrayList<EnvironmentStatus>();
        var projectOrder = projects.entrySet().stream().sorted(Map.Entry.<UUID, String>comparingByValue().thenComparing(Map.Entry.comparingByKey())).toList();
        for (var project : projectOrder) {
            var commits = linked.stream().filter(l -> l.projectId().equals(project.getKey())).toList();
            var repositories = commits.stream().map(Linked::repositoryId).collect(Collectors.toSet());
            for (var env : envs.stream().filter(e -> e.projectId().equals(project.getKey())).sorted(Comparator.comparingInt(EnvInfo::position)).toList()) {
                var in = includedIn.getOrDefault(env.id(), Set.of());
                int total = commits.size();
                int included = (int) commits.stream().filter(c -> in.contains(c.commitId())).count();
                var mine = waiting.stream().filter(w -> env.id().equals(w.get(CICD_DEPLOYMENTS.ENVIRONMENT_ID))
                        && repositories.contains(w.get(CICD_DEPLOYMENTS.REPOSITORY_ID))).toList();
                // what is known stays shown: every linked commit already in is in (the UI adds a note while a newer deployment is compared)
                boolean checking = mine.stream().anyMatch(w -> "checking".equals(w.get(CICD_DEPLOYMENTS.INCLUSION)));
                String state;
                if (included == total) state = "in";
                else if (checking) state = "checking";
                else if (included > 0) state = "partial";
                else if (mine.stream().anyMatch(w -> "failed".equals(w.get(CICD_DEPLOYMENTS.INCLUSION)))) state = "failed";
                else if (beforeFirst(commits, mine)) state = "before_first";
                else state = "none";
                var last = latest.get(env.id());
                out.add(new EnvironmentStatus(env.id(), env.name(), env.production(), state, included, total, partialCheck.contains(env.id()), checking,
                        last == null ? null : last.get(CICD_DEPLOYMENTS.DEPLOYED_AT), last == null ? "" : last.get(CICD_DEPLOYMENTS.WEB_URL),
                        projects.size() > 1 ? project.getValue() : null));
            }
        }
        var names = new HashMap<UUID, List<String>>();
        for (var id : shown) {
            var list = commitEnvs.get(id);
            if (list == null) continue;
            names.put(id, list.stream().sorted(Comparator.comparingInt(EnvInfo::position).reversed()).map(EnvInfo::name).toList());
        }
        return new Row(out, names);
    }

    /** Every linked commit is older than the cutoff of its repository's first deployment (the check could not look further back). */
    private static boolean beforeFirst(List<Linked> commits, List<Record> deployments) {
        var cutoffs = new HashMap<UUID, OffsetDateTime>();
        for (var d : deployments) {
            if (!"first".equals(d.get(CICD_DEPLOYMENTS.INCLUSION))) continue;
            cutoffs.merge(d.get(CICD_DEPLOYMENTS.REPOSITORY_ID), d.get(CICD_DEPLOYMENTS.FIRST_CUTOFF_AT), (a, b) -> a.isBefore(b) ? a : b);
        }
        return !commits.isEmpty() && commits.stream().allMatch(c -> {
            var cutoff = cutoffs.get(c.repositoryId());
            return cutoff != null && c.committedAt().isBefore(cutoff);
        });
    }

    /** One query for every person on the tab. */
    private Map<UUID, Dto.UserRef> users(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) return new HashMap<>();   // a plain map: get(null) is asked for commits without a person
        return db.select(USERS.ID, USERS.NAME).from(USERS).where(USERS.ID.in(ids)).fetch().stream()
                .collect(Collectors.toMap(r -> r.value1(), r -> new Dto.UserRef(r.value1(), r.value2())));
    }

    /** Trim, drop a #fragment and a ?query, drop trailing slashes. */
    static String normalize(String raw) {
        if (raw == null) return "";
        var s = raw.trim();
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** The stored MR of a repository attached inside the tree whose address is this one (or repository address + MR/PR number). */
    private UUID findMergeRequest(List<UUID> tree, String url) {
        var iid = CICD_MERGE_REQUESTS.IID.cast(String.class);
        var repoUrl = DSL.rtrim(CICD_REPOSITORIES.WEB_URL, "/");
        var match = DSL.rtrim(CICD_MERGE_REQUESTS.WEB_URL, "/").eq(url)
                .or(DSL.concat(repoUrl, DSL.inline("/-/merge_requests/"), iid).eq(url))
                .or(DSL.concat(repoUrl, DSL.inline("/pull/"), iid).eq(url));
        return db.select(CICD_MERGE_REQUESTS.ID).from(CICD_MERGE_REQUESTS)
                .join(CICD_REPOSITORIES).on(CICD_REPOSITORIES.ID.eq(CICD_MERGE_REQUESTS.REPOSITORY_ID))
                .where(CICD_REPOSITORIES.PROJECT_ID.in(tree), CICD_REPOSITORIES.DETACHED_AT.isNull(), match).orderBy(CICD_MERGE_REQUESTS.UPDATED_AT.desc()).limit(1)
                .fetchOne(CICD_MERGE_REQUESTS.ID);
    }
}
