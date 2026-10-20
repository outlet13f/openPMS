// CI/CD 3단계 seed for the fake layer (no imports of the database: seed.ts builds it). The same rows as the server's
// V1013__dev_cicd_releases.sql with readable ids: commits 5–13, their links, the 스테이징·운영 deployments 8006–8012 and what they
// brought in, 윤서아's five verifications on 스테이징 and two release records. The board of web-platform (스테이징 → 운영, version
// "2026 3분기"): waiting #11, re-verify #1, failed #4, passed #3·#5; missing #6 (no code); interloper #11; one unlinked commit.
// The baseline (운영's first deployment, 10-13T05:00Z) keeps commits 1·2·12 off the board. The journals and the failure
// notifications of the verifications (박준호 and the co-assignee 이하늘 on #4) are added by seed.ts, with QA 윤서아 and the QA role.
import type { Id, IsoDateTime, ReleaseWarning } from "../types";
import { CICD, type DbCicdCommit, type DbCicdDeployment, type DbCicdEnvironmentCommit, type DbCicdInclusion, type DbCicdLink } from "./cicd-seed";

/** cicd_verifications: one result of a work package on a source environment; basisAt = when its commits last came in then */
export interface DbCicdVerification {
  id: Id;
  wpId: Id;
  environmentId: Id;
  result: "passed" | "failed";
  /** "" when passed */
  reason: string;
  verifiedBy: Id | null;
  verifiedAt: IsoDateTime;
  basisAt: IsoDateTime;
}
/** cicd_board_exclusions: "이미 운영에 있음" on the board S → T, until a commit of it comes into S after addedBefore */
export interface DbCicdBoardExclusion {
  wpId: Id;
  environmentId: Id;
  targetEnvironmentId: Id;
  excludedBy: Id | null;
  excludedAt: IsoDateTime;
  addedBefore: IsoDateTime;
}
/** cicd_releases: a successful deployment to a production environment that brought commits in (read-only history) */
export interface DbCicdRelease {
  id: Id;
  projectId: Id;
  environmentId: Id;
  /** the default source environment when it was recorded; null = none */
  sourceEnvironmentId: Id | null;
  deploymentId: Id;
  repositoryId: Id;
  deployedAt: IsoDateTime;
  createdAt: IsoDateTime;
  partial: boolean;
  contentFailed: boolean;
}
/** cicd_release_items: copies (number, subject, verification, warning) made when the release was recorded; wpId null = deleted since */
export interface DbCicdReleaseItem {
  releaseId: Id;
  wpId: Id | null;
  number: number;
  subject: string;
  verificationId: Id | null;
  warning: ReleaseWarning | null;
}

const GITLAB = "https://gitlab.example.invalid";
/** web-platform's environments in cicd-seed.ts */
export const RELEASE_ENV = { staging: "cicd-env-web-2", production: "cicd-env-web-3" } as const;

export function seedReleases() {
  const commit = (n: number, sha: string, title: string, committedAt: string): DbCicdCommit => ({
    id: `cicd-commit-${n}`, repositoryId: CICD.repoWeb, sha, title, authorName: "박준호", userId: "u-junho", committedAt, webUrl: `${GITLAB}/web/portal/-/commit/${sha}`,
  });
  // 작성 시각은 그 커밋이 스테이징에 들어가기 30분 전
  const commits: DbCicdCommit[] = [
    commit(5, "075c202cfe6aaf5c29b6fc5d8af2a0e7616e3f14", "화면 설계 검토 반영 (#3)", "2026-10-16T01:30:00Z"),
    commit(6, "ad3c86a35f969db1685b506b126023e38d5039f7", "릴리스 노트 초안 (#5)", "2026-10-16T01:30:00Z"),
    commit(7, "6b4a7a20440c37d8434f99ecb968540a97730238", "결제 실패 문구 정리 (#4)", "2026-10-16T01:30:00Z"),
    commit(8, "f3139102138a4ea4a56d34020f2d73f422660598", "릴리스 준비 체크리스트 (#1)", "2026-10-16T01:30:00Z"),
    commit(9, "d1ec4a55ddc65350f2e03c6cb8fa660c45cedcef", "릴리스 준비 체크리스트 보완 (#1)", "2026-10-18T02:30:00Z"),
    commit(10, "e00f05f4af446315970b6178a94e39c10f950bf9", "검색 정렬 기준 고침 (#11)", "2026-10-18T02:30:00Z"),
    commit(11, "593c6088e7451233ae71aeb491ae654bfccb521b", "의존성 정리", "2026-10-18T02:30:00Z"),
    commit(12, "c09fa833f61e5e3c35dd77b13d277e4ddb51e29b", "배포 스크립트 정리 (#13)", "2026-10-13T03:30:00Z"),
    commit(13, "a0b874060599719d7e0894ad5dce099e375b53e4", "로그인 대비 재수정 (#8)", "2026-10-14T03:30:00Z"),
  ];
  const commitOf = (n: number) => commits.find((c) => c.id === `cicd-commit-${n}`);
  // 커밋 ↔ 작업 연결 (커밋 11은 연결 없음); 연결 시각은 커밋 1분 뒤
  const plusMinute = (at: string) => new Date(Date.parse(at) + 60_000).toISOString().replace(".000Z", "Z");
  const links: DbCicdLink[] = ([[8, 5, 3], [9, 6, 5], [10, 7, 4], [11, 8, 1], [12, 9, 1], [13, 10, 11], [14, 12, 13], [15, 13, 8]] as const).map(([n, c, wp]) => ({
    id: `cicd-link-${n}`, wpId: `wp-${wp}`, commitId: `cicd-commit-${c}`, mergeRequestId: null, source: "message", createdBy: null,
    createdAt: plusMinute(commitOf(c)!.committedAt), dismissedBy: null, dismissedAt: null,
  }));

  // 배포: 스테이징·운영 (커밋 1·2는 cicd-seed.ts의 것)
  const shaOf = (n: number) => commitOf(n)?.sha ?? (n === 2 ? "2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e" : "");
  const deployment = (n: number, production: boolean, commitN: number, deployedAt: string, inclusion: DbCicdInclusion): DbCicdDeployment => ({
    id: `cicd-deploy-${8000 + n}`, repositoryId: CICD.repoWeb, environmentId: production ? RELEASE_ENV.production : RELEASE_ENV.staging,
    environmentName: production ? "운영" : "스테이징", externalId: String(8000 + n), sha: shaOf(commitN), status: "success",
    webUrl: `${GITLAB}/web/portal/-/environments/${8000 + n}`, deployedAt, source: "gitlab", inclusion, firstCutoffAt: null,
  });
  const deployments: DbCicdDeployment[] = [
    deployment(6, false, 12, "2026-10-13T04:00:00Z", "compared"),
    deployment(7, true, 2, "2026-10-13T05:00:00Z", "first"),
    deployment(8, false, 13, "2026-10-14T04:00:00Z", "compared"),
    deployment(9, true, 12, "2026-10-14T05:00:00Z", "compared"),
    deployment(10, true, 13, "2026-10-15T05:00:00Z", "compared"),
    deployment(11, false, 8, "2026-10-16T02:00:00Z", "compared"),
    deployment(12, false, 11, "2026-10-18T03:00:00Z", "compared"),
  ];
  // 배포가 새로 들여온 커밋: 06 = 12, 07 = 1·2(첫 배포), 08 = 13, 09 = 12, 10 = 13, 11 = 5–8, 12 = 9–11
  const brought: [number, number[]][] = [[6, [12]], [7, [1, 2]], [8, [13]], [9, [12]], [10, [13]], [11, [5, 6, 7, 8]], [12, [9, 10, 11]]];
  const environmentCommits: DbCicdEnvironmentCommit[] = brought.flatMap(([d, list]) => {
    const dep = deployments.find((x) => x.id === `cicd-deploy-${8000 + d}`)!;
    return list.map((c) => ({ environmentId: dep.environmentId!, repositoryId: CICD.repoWeb, commitId: `cicd-commit-${c}`, deploymentId: dep.id, addedAt: dep.deployedAt }));
  });

  // 검증 5건 (스테이징, 윤서아)
  const verification = (n: number, wp: number, result: "passed" | "failed", reason: string, verifiedAt: string, basisAt: string): DbCicdVerification => ({
    id: `cicd-ver-${n}`, wpId: `wp-${wp}`, environmentId: RELEASE_ENV.staging, result, reason, verifiedBy: CICD.qaUser, verifiedAt, basisAt,
  });
  const verifications: DbCicdVerification[] = [
    verification(1, 13, "passed", "", "2026-10-13T09:00:00Z", "2026-10-13T04:00:00Z"),
    verification(2, 3, "passed", "", "2026-10-17T01:00:00Z", "2026-10-16T02:00:00Z"),
    verification(3, 5, "passed", "", "2026-10-17T01:10:00Z", "2026-10-16T02:00:00Z"),
    verification(4, 4, "failed", "결제 실패 때 오류 문구가 영어로 나옵니다.", "2026-10-17T01:20:00Z", "2026-10-16T02:00:00Z"),
    verification(5, 1, "passed", "", "2026-10-17T01:30:00Z", "2026-10-16T02:00:00Z"),
  ];
  const exclusions: DbCicdBoardExclusion[] = [];

  // 릴리스 기록 2건: 배포 09(운영, 원본 스테이징) 항목 #13(검증 1), 배포 10 항목 #8(검증 없음 = unverified)
  const release = (n: number, d: number): DbCicdRelease => {
    const dep = deployments.find((x) => x.id === `cicd-deploy-${8000 + d}`)!;
    return {
      id: `cicd-rel-${n}`, projectId: "p-web", environmentId: RELEASE_ENV.production, sourceEnvironmentId: RELEASE_ENV.staging, deploymentId: dep.id,
      repositoryId: CICD.repoWeb, deployedAt: dep.deployedAt, createdAt: dep.deployedAt, partial: false, contentFailed: false,
    };
  };
  const releases: DbCicdRelease[] = [release(1, 9), release(2, 10)];
  const releaseItems: DbCicdReleaseItem[] = [
    { releaseId: "cicd-rel-1", wpId: "wp-13", number: 13, subject: "배포 자동화 스크립트 정리", verificationId: "cicd-ver-1", warning: null },
    { releaseId: "cicd-rel-2", wpId: "wp-8", number: 8, subject: "로그인 화면 글자 대비 부족", verificationId: null, warning: "unverified" },
  ];
  return { commits, links, deployments, environmentCommits, verifications, exclusions, releases, releaseItems };
}
