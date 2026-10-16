package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_MERGE_REQUESTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGE_CICD_LINKS;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.core.Current;
import com.openpms.wp.WorkPackageService;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    public record MrDto(UUID linkId, String repository, long iid, String title, String state, String sourceBranch, String targetBranch,
                        String author, Dto.UserRef user, OffsetDateTime mergedAt, OffsetDateTime updatedAt, String webUrl, String source) {}

    public record CommitDto(UUID linkId, String repository, String shortSha, String title, String author, Dto.UserRef user,
                            OffsetDateTime committedAt, String webUrl, String source) {}

    public record DevInfo(List<MrDto> mergeRequests, List<CommitDto> commits, boolean canLink) {}

    public record LinkIn(String url) {}

    private final Current current;
    private final WorkPackageService workPackages;
    private final CicdLinker linker;
    private final DSLContext db;

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
        var commitRows = db.select(WORK_PACKAGE_CICD_LINKS.ID, CICD_REPOSITORIES.FULL_PATH, CICD_COMMITS.SHA, CICD_COMMITS.TITLE,
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
        var mrs = mrRows.stream().map(r -> mrDto(r, users)).toList();
        var commits = commitRows.stream().map(r -> new CommitDto(r.get(WORK_PACKAGE_CICD_LINKS.ID), r.get(CICD_REPOSITORIES.FULL_PATH),
                r.get(CICD_COMMITS.SHA).substring(0, Math.min(7, r.get(CICD_COMMITS.SHA).length())), r.get(CICD_COMMITS.TITLE),
                r.get(CICD_COMMITS.AUTHOR_NAME), users.get(r.get(CICD_COMMITS.USER_ID)), r.get(CICD_COMMITS.COMMITTED_AT),
                r.get(CICD_COMMITS.WEB_URL), r.get(WORK_PACKAGE_CICD_LINKS.SOURCE))).toList();
        return new DevInfo(mrs, commits, current.ctx().can(found.project(), "cicd.link"));
    }

    @PostMapping("/links")
    public ResponseEntity<MrDto> link(@PathVariable long number, @RequestBody LinkIn in) {
        var found = linkable(number);
        var wpId = found.wp().getId();
        var url = normalize(in == null ? null : in.url());
        // only the ancestors whose repositories this person may see: a member of a private child is not shown the parent's MRs
        var ctx = current.ctx();
        var tree = linker.projectAncestors(found.project().id()).stream().filter(id -> {
            var p = ctx.s().projects().get(id);
            return p != null && ctx.can(p, "cicd.view");
        }).toList();
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
        return ResponseEntity.status(HttpStatus.CREATED).body(mrDto(row, users(ids)));
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
        return db.select(WORK_PACKAGE_CICD_LINKS.ID, CICD_REPOSITORIES.FULL_PATH, CICD_MERGE_REQUESTS.IID, CICD_MERGE_REQUESTS.TITLE,
                        CICD_MERGE_REQUESTS.STATE, CICD_MERGE_REQUESTS.SOURCE_BRANCH, CICD_MERGE_REQUESTS.TARGET_BRANCH,
                        CICD_MERGE_REQUESTS.AUTHOR_NAME, CICD_MERGE_REQUESTS.USER_ID, CICD_MERGE_REQUESTS.MERGED_AT,
                        CICD_MERGE_REQUESTS.UPDATED_AT, CICD_MERGE_REQUESTS.WEB_URL, WORK_PACKAGE_CICD_LINKS.SOURCE)
                .from(WORK_PACKAGE_CICD_LINKS).join(CICD_MERGE_REQUESTS).on(CICD_MERGE_REQUESTS.ID.eq(WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID))
                .join(CICD_REPOSITORIES).on(CICD_REPOSITORIES.ID.eq(CICD_MERGE_REQUESTS.REPOSITORY_ID))
                .where(where).orderBy(CICD_MERGE_REQUESTS.UPDATED_AT.desc(), CICD_MERGE_REQUESTS.ID).fetch().map(r -> (Record) r);
    }

    private static MrDto mrDto(Record r, Map<UUID, Dto.UserRef> users) {
        return new MrDto(r.get(WORK_PACKAGE_CICD_LINKS.ID), r.get(CICD_REPOSITORIES.FULL_PATH), r.get(CICD_MERGE_REQUESTS.IID),
                r.get(CICD_MERGE_REQUESTS.TITLE), r.get(CICD_MERGE_REQUESTS.STATE), r.get(CICD_MERGE_REQUESTS.SOURCE_BRANCH),
                r.get(CICD_MERGE_REQUESTS.TARGET_BRANCH), r.get(CICD_MERGE_REQUESTS.AUTHOR_NAME), users.get(r.get(CICD_MERGE_REQUESTS.USER_ID)),
                r.get(CICD_MERGE_REQUESTS.MERGED_AT), r.get(CICD_MERGE_REQUESTS.UPDATED_AT), r.get(CICD_MERGE_REQUESTS.WEB_URL),
                r.get(WORK_PACKAGE_CICD_LINKS.SOURCE));
    }

    /** One query for every person on the tab. */
    private Map<UUID, Dto.UserRef> users(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
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
                .where(CICD_REPOSITORIES.PROJECT_ID.in(tree), match).orderBy(CICD_MERGE_REQUESTS.UPDATED_AT.desc()).limit(1)
                .fetchOne(CICD_MERGE_REQUESTS.ID);
    }
}
