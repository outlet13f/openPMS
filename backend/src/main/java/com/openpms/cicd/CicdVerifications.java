package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_BOARD_EXCLUSIONS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_VERIFICATIONS;
import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.cicd.CicdReleaseController.Excluded;
import com.openpms.cicd.CicdReleaseController.VerificationDto;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot.ProjectRow;
import com.openpms.db.tables.records.WorkPackagesRecord;
import com.openpms.wp.Assignees;
import com.openpms.wp.Journals;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

/**
 * Verifying a work package on a release board (S → T), excluding it from the board ("이미 운영에 있음") and restoring it
 * (design 4.2). The callers hold one transaction. The failure reason is user input: it goes to the table, the journal
 * and the response only, never to a log line or an exception message (the check errors are fixed sentences).
 */
@Service
public class CicdVerifications {
    private static final int REASON_MAX = 2000;
    private static final String NOT_ON_BOARD = "이 보드에 없는 작업입니다. 이미 대상 환경에 들어갔거나 연결이 바뀌었습니다.";

    private final DSLContext db;
    private final CicdReleaseBoard board;
    private final Journals journals;
    private final Assignees assignees;

    public CicdVerifications(DSLContext db, CicdReleaseBoard board, Journals journals, Assignees assignees) {
        this.db = db;
        this.board = board;
        this.journals = journals;
        this.assignees = assignees;
    }

    private record Target(WorkPackagesRecord wp, ProjectRow project) {}

    private record Request(UUID workPackageId, UUID source, UUID target) {}

    private static void mayVerify(Ctx ctx, ProjectRow p) {
        ctx.require(p, "release.verify", "릴리스를 검증할 권한이 없습니다.");
    }

    private static Request request(Map<String, Object> in) {
        var wp = In.uuid(in, "workPackageId", "workPackageId");
        var source = In.uuid(in, "environmentId", "environmentId");
        var target = In.uuid(in, "targetEnvironmentId", "targetEnvironmentId");
        if (wp == null) throw ApiException.field("workPackageId", "작업을 고르세요.");
        if (source == null) throw ApiException.field("environmentId", "환경을 고르세요.");
        if (target == null) throw ApiException.field("targetEnvironmentId", "환경을 고르세요.");
        return new Request(wp, source, target);
    }

    /** The work package, when it exists and I may see it; otherwise the same 404 (its existence does not show). */
    private Target workPackage(Ctx ctx, UUID wpId) {
        var wp = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(wpId)).fetchOne();
        var project = wp == null ? null : ctx.s().project(wp.getProjectId()).orElse(null);
        if (project == null || !ctx.can(project, wpId, "wp.view")) throw ApiException.notFound("작업을 찾을 수 없습니다.");
        return new Target(wp, project);
    }

    public VerificationDto verify(Ctx ctx, ProjectRow p, Map<String, Object> in) {
        mayVerify(ctx, p);
        var result = In.str(in, "result");
        if (!"passed".equals(result) && !"failed".equals(result))
            throw ApiException.field("result", "결과는 통과(passed)나 실패(failed)여야 합니다.");
        // trimmed once; the same value is checked and stored, so the table's check never fires (and never logs it)
        String reason = "";
        if ("failed".equals(result)) {
            var raw = In.str(in, "reason");
            reason = raw == null ? "" : raw.strip();
            if (reason.isEmpty()) throw ApiException.field("reason", "실패 사유를 적으세요.");
            if (reason.codePointCount(0, reason.length()) > REASON_MAX) throw ApiException.field("reason", "실패 사유는 2,000자까지 적을 수 있습니다.");
        }
        var req = request(in);
        var target = workPackage(ctx, req.workPackageId());
        var pair = board.pair(p, req.source(), req.target());
        var basisAt = board.listed(ctx, p, pair.source(), pair.target()).get(req.workPackageId());
        if (basisAt == null) throw ApiException.unprocessable(NOT_ON_BOARD, null);

        var row = db.insertInto(CICD_VERIFICATIONS)
                .set(CICD_VERIFICATIONS.WORK_PACKAGE_ID, req.workPackageId()).set(CICD_VERIFICATIONS.ENVIRONMENT_ID, pair.source())
                .set(CICD_VERIFICATIONS.RESULT, result).set(CICD_VERIFICATIONS.REASON, reason).set(CICD_VERIFICATIONS.VERIFIED_BY, ctx.userId())
                .set(CICD_VERIFICATIONS.BASIS_AT, basisAt)
                .returning(CICD_VERIFICATIONS.ID, CICD_VERIFICATIONS.VERIFIED_AT).fetchOne();
        var environment = db.select(CICD_ENVIRONMENTS.NAME).from(CICD_ENVIRONMENTS).where(CICD_ENVIRONMENTS.ID.eq(pair.source())).fetchOne(CICD_ENVIRONMENTS.NAME);

        // one activity line (보완 11), no field changes and no webhook
        var cause = new LinkedHashMap<String, Object>();
        cause.put("kind", "failed".equals(result) ? "verification_failed" : "verification_passed");
        cause.put("environment", environment);
        if ("failed".equals(result)) cause.put("reason", reason);
        var journal = journals.write(ctx, target.wp(), new LinkedHashMap<>(), "", cause, false);
        if ("failed".equals(result)) notifyAssignees(ctx, target, journal.getId(), journal.getUpdatedAt());

        return new VerificationDto(row.getId(), result, reason, Refs.user(ctx.s(), ctx.userId()), row.getVerifiedAt(), environment);
    }

    /**
     * 보완 10: the assignees and co-assignees who are active and may see the work package, not the one who verified. Always on
     * (as mentions); the mails follow the usual notification mail settings.
     */
    private void notifyAssignees(Ctx ctx, Target target, UUID journalId, OffsetDateTime at) {
        var s = ctx.s();
        var wpId = target.wp().getId();
        for (var userId : Assignees.ids(assignees.of(wpId))) {
            if (userId.equals(ctx.userId())) continue;
            var u = s.user(userId).filter(x -> "active".equals(x.status())).orElse(null);
            if (u == null || !ctx.can(u, target.project(), wpId, "wp.view")) continue;
            db.insertInto(NOTIFICATIONS)
                    .set(NOTIFICATIONS.RECIPIENT_ID, userId).set(NOTIFICATIONS.WORK_PACKAGE_ID, wpId).set(NOTIFICATIONS.PROJECT_ID, target.wp().getProjectId())
                    .set(NOTIFICATIONS.JOURNAL_ID, journalId).set(NOTIFICATIONS.ACTOR_ID, ctx.userId()).set(NOTIFICATIONS.REASON, "verification_failed")
                    .set(NOTIFICATIONS.CREATED_AT, at)
                    .execute();
        }
    }

    public Excluded exclude(Ctx ctx, ProjectRow p, Map<String, Object> in) {
        mayVerify(ctx, p);
        var req = request(in);
        var target = workPackage(ctx, req.workPackageId());
        var pair = board.pair(p, req.source(), req.target());
        // on the board before exclusions: excluding it again just renews the row
        var addedBefore = board.listedBeforeExclusions(ctx, p, pair.source(), pair.target()).get(req.workPackageId());
        if (addedBefore == null) throw ApiException.unprocessable(NOT_ON_BOARD, null);
        var at = db.insertInto(CICD_BOARD_EXCLUSIONS)
                .set(CICD_BOARD_EXCLUSIONS.WORK_PACKAGE_ID, req.workPackageId()).set(CICD_BOARD_EXCLUSIONS.ENVIRONMENT_ID, pair.source())
                .set(CICD_BOARD_EXCLUSIONS.TARGET_ENVIRONMENT_ID, pair.target()).set(CICD_BOARD_EXCLUSIONS.EXCLUDED_BY, ctx.userId())
                .set(CICD_BOARD_EXCLUSIONS.EXCLUDED_AT, DSL.currentOffsetDateTime()).set(CICD_BOARD_EXCLUSIONS.ADDED_BEFORE, addedBefore)
                .onConflict(CICD_BOARD_EXCLUSIONS.WORK_PACKAGE_ID, CICD_BOARD_EXCLUSIONS.ENVIRONMENT_ID, CICD_BOARD_EXCLUSIONS.TARGET_ENVIRONMENT_ID)
                .doUpdate().set(CICD_BOARD_EXCLUSIONS.EXCLUDED_BY, DSL.excluded(CICD_BOARD_EXCLUSIONS.EXCLUDED_BY))
                .set(CICD_BOARD_EXCLUSIONS.EXCLUDED_AT, DSL.excluded(CICD_BOARD_EXCLUSIONS.EXCLUDED_AT))
                .set(CICD_BOARD_EXCLUSIONS.ADDED_BEFORE, DSL.excluded(CICD_BOARD_EXCLUSIONS.ADDED_BEFORE))
                .returning(CICD_BOARD_EXCLUSIONS.EXCLUDED_AT).fetchOne().getExcludedAt();
        var wp = target.wp();
        return new Excluded(wp.getId(), wp.getNumber(), wp.getSubject(), Refs.user(ctx.s(), ctx.userId()), at);
    }

    /** Puts an excluded work package back on the board. Only this project's boards; 404 when there is nothing to restore. */
    public void restore(Ctx ctx, ProjectRow p, UUID workPackageId, UUID source, UUID target) {
        mayVerify(ctx, p);
        workPackage(ctx, workPackageId);
        int deleted = db.deleteFrom(CICD_BOARD_EXCLUSIONS)
                .where(CICD_BOARD_EXCLUSIONS.WORK_PACKAGE_ID.eq(workPackageId), CICD_BOARD_EXCLUSIONS.ENVIRONMENT_ID.eq(source),
                        CICD_BOARD_EXCLUSIONS.TARGET_ENVIRONMENT_ID.eq(target),
                        CICD_BOARD_EXCLUSIONS.ENVIRONMENT_ID.in(db.select(CICD_ENVIRONMENTS.ID).from(CICD_ENVIRONMENTS).where(CICD_ENVIRONMENTS.PROJECT_ID.eq(p.id()))))
                .execute();
        if (deleted == 0) throw ApiException.notFound("뺀 작업을 찾을 수 없습니다.");
    }
}
