package com.openpms.cicd;

import com.openpms.api.Dto;
import com.openpms.core.Current;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로젝트 › 릴리스: the release verification board. Viewing needs cicd.view (module on), as the rest of CI/CD; the cards are
 * filtered per work package by wp.view.
 */
@RestController
@RequestMapping("/api/v1/projects/{project}/releases")
public class CicdReleaseController {
    public record EnvironmentRef(UUID id, String name, int position, boolean production, boolean hidden) {}

    public record VersionRef(UUID id, String name, String status, LocalDate endDate) {}

    public record VerificationDto(UUID id, String result, String reason, Dto.UserRef by, OffsetDateTime at, String environment) {}

    public record Card(UUID workPackageId, long number, String subject, Dto.StatusRef status, List<Dto.UserRef> assignees, Dto.ProjectRef project,
                       String column, OffsetDateTime cameInAt, String deploymentUrl, VerificationDto last, boolean interloper) {}

    /** group: developing | no_code */
    public record Missing(UUID workPackageId, long number, String subject, Dto.StatusRef status, String group) {}

    public record UnlinkedCommit(String repository, String shortSha, String title, String webUrl, OffsetDateTime cameInAt) {}

    public record Excluded(UUID workPackageId, long number, String subject, Dto.UserRef by, OffsetDateTime at) {}

    /** kind: checking | failed | first_cutoff | partial */
    public record Notice(UUID environmentId, String environment, String kind) {}

    public record Board(List<EnvironmentRef> environments, UUID sourceId, UUID targetId, List<VersionRef> versions, UUID versionId,
                        List<Card> cards, List<Missing> missing, int interloperCount, int unlinkedCount, List<UnlinkedCommit> unlinked,
                        List<Excluded> excluded, List<Notice> notices, boolean canVerify) {}

    private final Current current;
    private final CicdRepositories repositories;
    private final CicdReleaseBoard board;

    public CicdReleaseController(Current current, CicdRepositories repositories, CicdReleaseBoard board) {
        this.current = current;
        this.repositories = repositories;
        this.board = board;
    }

    /** source, target: environments of the project (default: the first production one and the one before it). version: an id, "none", or the default. */
    @GetMapping("/board")
    @Transactional(readOnly = true)
    public Board board(@PathVariable String project, @RequestParam(required = false) UUID source, @RequestParam(required = false) UUID target,
                       @RequestParam(required = false) String version) {
        var ctx = current.ctx();
        return board.board(ctx, repositories.viewable(ctx, project), source, target, version);
    }
}
