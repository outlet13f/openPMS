// CI/CD 연동 seed for the fake layer (no imports of the database: seed.ts builds it). The same rows as the server's
// V1010__dev_cicd.sql (one connection without secrets, two repositories, four commits, two merge requests and the links on
// web-platform #4, #8, #11 and the dismissed one on #13), with readable ids instead of uuids. V26's roles are added by seed.ts.
import type { CicdKind, CicdLinkSource, Id, IsoDateTime } from "../types";

/** secrets are never kept: only the hint (last four characters), null = nothing stored */
export interface DbCicdConnection {
  id: Id;
  kind: CicdKind;
  name: string;
  baseUrl: string;
  hookKey: string;
  tokenHint: string | null;
  secretHint: string | null;
  active: boolean;
  createdBy: Id | null;
  createdAt: IsoDateTime;
}
export interface DbCicdRepository {
  id: Id;
  connectionId: Id;
  externalId: string;
  fullPath: string;
  webUrl: string;
  projectId: Id;
  lastPolledAt: IsoDateTime | null;
  createdAt: IsoDateTime;
}
export interface DbCicdCommit {
  id: Id;
  repositoryId: Id;
  sha: string;
  title: string;
  authorName: string;
  userId: Id | null;
  committedAt: IsoDateTime;
  webUrl: string;
}
export interface DbCicdMergeRequest {
  id: Id;
  repositoryId: Id;
  iid: number;
  title: string;
  state: "open" | "draft" | "merged" | "closed";
  sourceBranch: string;
  targetBranch: string;
  authorName: string;
  userId: Id | null;
  mergedAt: IsoDateTime | null;
  updatedAt: IsoDateTime;
  webUrl: string;
}
/** work_package_cicd_links: exactly one of commitId / mergeRequestId; dismissedAt set = cut off by a person */
export interface DbCicdLink {
  id: Id;
  wpId: Id;
  commitId: Id | null;
  mergeRequestId: Id | null;
  source: CicdLinkSource;
  createdBy: Id | null;
  createdAt: IsoDateTime;
  dismissedBy: Id | null;
  dismissedAt: IsoDateTime | null;
}

export const CICD = {
  connection: "cicd-conn-gitlab",
  repoWeb: "cicd-repo-web",
  repoMobile: "cicd-repo-api",
  devLeadRole: "r-devlead",
  cicdAdminRole: "r-cicd-admin",
} as const;

const GITLAB = "https://gitlab.example.invalid";

export function seedCicd() {
  const connections: DbCicdConnection[] = [
    {
      id: CICD.connection, kind: "gitlab", name: "사내 GitLab(개발용)", baseUrl: GITLAB, hookKey: "dev-gitlab-hook-key", tokenHint: null, secretHint: null,
      active: false, createdBy: "u-seoyeon", createdAt: "2026-10-01T00:30:00Z",
    },
  ];
  const repositories: DbCicdRepository[] = [
    {
      id: CICD.repoWeb, connectionId: CICD.connection, externalId: "101", fullPath: "web/portal", webUrl: `${GITLAB}/web/portal`, projectId: "p-web",
      lastPolledAt: null, createdAt: "2026-10-01T00:35:00Z",
    },
    {
      id: CICD.repoMobile, connectionId: CICD.connection, externalId: "102", fullPath: "mobile/app", webUrl: `${GITLAB}/mobile/app`, projectId: "p-mobile",
      lastPolledAt: null, createdAt: "2026-10-01T00:36:00Z",
    },
  ];
  const commit = (n: number, sha: string, title: string, authorName: string, userId: Id, committedAt: string): DbCicdCommit => ({
    id: `cicd-commit-${n}`, repositoryId: CICD.repoWeb, sha, title, authorName, userId, committedAt, webUrl: `${GITLAB}/web/portal/-/commit/${sha}`,
  });
  const commits: DbCicdCommit[] = [
    commit(1, "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d", "결제 API 클라이언트 추가 (#4)", "박준호", "u-junho", "2026-10-05T02:10:00Z"),
    commit(2, "2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e", "결제 승인 응답 처리 (#4)", "박준호", "u-junho", "2026-10-06T05:45:00Z"),
    commit(3, "3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f", "로그인 화면 글자 색 대비 개선 (#8)", "이하늘", "u-haneul", "2026-10-07T01:20:00Z"),
    commit(4, "4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f70", "검색 결과 날짜 정렬 수정 (#11)", "Alex Kim", "u-alex", "2026-10-08T07:05:00Z"),
  ];
  const mergeRequests: DbCicdMergeRequest[] = [
    {
      id: "cicd-mr-12", repositoryId: CICD.repoWeb, iid: 12, title: "결제 API 연동 (#4)", state: "merged", sourceBranch: "feature/4-payment-api", targetBranch: "main",
      authorName: "박준호", userId: "u-junho", mergedAt: "2026-10-07T03:00:00Z", updatedAt: "2026-10-07T03:00:00Z", webUrl: `${GITLAB}/web/portal/-/merge_requests/12`,
    },
    {
      id: "cicd-mr-13", repositoryId: CICD.repoWeb, iid: 13, title: "로그인 화면 대비 개선", state: "open", sourceBranch: "fix/8-login-contrast", targetBranch: "main",
      authorName: "이하늘", userId: "u-haneul", mergedAt: null, updatedAt: "2026-10-09T04:30:00Z", webUrl: `${GITLAB}/web/portal/-/merge_requests/13`,
    },
  ];
  const link = (n: number, wp: number, commitId: Id | null, mergeRequestId: Id | null, source: CicdLinkSource, createdAt: string, dismissed = false): DbCicdLink => ({
    id: `cicd-link-${n}`, wpId: `wp-${wp}`, commitId, mergeRequestId, source, createdBy: null, createdAt,
    dismissedBy: dismissed ? "u-seoyeon" : null, dismissedAt: dismissed ? "2026-10-09T01:00:00Z" : null,
  });
  const links: DbCicdLink[] = [
    link(1, 4, "cicd-commit-1", null, "message", "2026-10-05T02:11:00Z"),
    link(2, 4, "cicd-commit-2", null, "message", "2026-10-06T05:46:00Z"),
    link(3, 4, null, "cicd-mr-12", "branch", "2026-10-07T00:00:00Z"),
    link(4, 8, "cicd-commit-3", null, "message", "2026-10-07T01:21:00Z"),
    link(5, 8, null, "cicd-mr-13", "branch", "2026-10-09T04:31:00Z"),
    link(6, 11, "cicd-commit-4", null, "message", "2026-10-08T07:06:00Z"),
    // 끊은 연결: 13번 작업이 커밋 4를 잘못 잇던 것을 서연이 끊음
    link(7, 13, "cicd-commit-4", null, "message", "2026-10-08T07:06:00Z", true),
  ];
  return { connections, repositories, commits, mergeRequests, links };
}
