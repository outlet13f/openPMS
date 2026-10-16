// CI/CD 연동 seed for the fake layer (no imports of the database: seed.ts builds it). The same rows as the server's
// V1010__dev_cicd.sql (one connection without secrets, two repositories, four commits, two merge requests and the links on
// web-platform #4, #8, #11 and the dismissed one on #13), with readable ids instead of uuids, and V1012's builds, deployments and
// environments (개발 ✓ · 스테이징 ✓ · 운영 – on #4; #8 nowhere; #11 개발 ✓ only; two deployments to the unassigned "qa-env").
// V26's roles are added by seed.ts.
import type { CicdBuildStatus, CicdKind, CicdLinkSource, Id, IsoDateTime } from "../types";

/** secrets are never kept: only the hint (last four characters), null = nothing stored */
export interface DbCicdConnection {
  id: Id;
  kind: CicdKind;
  name: string;
  baseUrl: string;
  hookKey: string;
  /** Jenkins·Argo CD: the address links must be on (= baseUrl); null for GitLab·GitHub */
  toolUrl?: string | null;
  /** Jenkins·Argo CD: the commit number rule; null/absent = the default */
  imageTagPattern?: string | null;
  tokenHint: string | null;
  secretHint: string | null;
  active: boolean;
  createdBy: Id | null;
  createdAt: IsoDateTime;
}
/** cicd_connection_projects: a project on a connection's allowed list */
export interface DbCicdConnectionProject {
  connectionId: Id;
  projectId: Id;
}
export interface DbCicdRepository {
  id: Id;
  connectionId: Id;
  externalId: string;
  fullPath: string;
  webUrl: string;
  projectId: Id;
  lastPolledAt: IsoDateTime | null;
  /** set = detached (soft): the row and its history stay; absent = attached */
  detachedAt?: IsoDateTime | null;
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
  /** the commit a merged request made (the full sha); its linked commit counts for the work package too */
  mergeCommitSha?: string | null;
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

/** cicd_environments: a project's environment; aliases are lower case; hidden = kept for its history, takes nothing new */
export interface DbCicdEnvironment {
  id: Id;
  projectId: Id;
  name: string;
  position: number;
  production: boolean;
  aliases: string[];
  hidden: boolean;
}
/** cicd_pipelines: one workflow run of a commit (sha) on a branch (ref) */
export interface DbCicdPipeline {
  id: Id;
  repositoryId: Id;
  externalId: string;
  name: string;
  sha: string;
  ref: string;
  status: CicdBuildStatus;
  webUrl: string;
  startedAt: IsoDateTime | null;
  finishedAt: IsoDateTime | null;
  source: "gitlab" | "github" | "jenkins";
  updatedAt: IsoDateTime;
}
/** how the commits of a deployment were found: first (cutoff = how far back it could look), compared, partial, checking, failed, unknown_commit; null = none */
export type DbCicdInclusion = "first" | "compared" | "partial" | "checking" | "failed" | "unknown_commit";
/** cicd_deployments: environmentId null = no environment answers to environmentName (unassigned) */
export interface DbCicdDeployment {
  id: Id;
  repositoryId: Id;
  environmentId: Id | null;
  environmentName: string;
  externalId: string;
  sha: string | null;
  status: "success" | "failed" | "running";
  webUrl: string;
  deployedAt: IsoDateTime;
  source: "gitlab" | "github" | "jenkins" | "argocd";
  inclusion: DbCicdInclusion | null;
  firstCutoffAt?: IsoDateTime | null;
}
/** cicd_environment_commits: a commit that is in an environment now, and the deployment that took it in */
export interface DbCicdEnvironmentCommit {
  environmentId: Id;
  repositoryId: Id;
  commitId: Id;
  deploymentId: Id;
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
  // V1011: the projects with V1010's repositories are on the list
  const connectionProjects: DbCicdConnectionProject[] = [
    { connectionId: CICD.connection, projectId: "p-web" },
    { connectionId: CICD.connection, projectId: "p-mobile" },
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
      mergeCommitSha: commits[1].sha, id: "cicd-mr-12", repositoryId: CICD.repoWeb, iid: 12, title: "결제 API 연동 (#4)", state: "merged", sourceBranch: "feature/4-payment-api", targetBranch: "main",
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

  // V1012: the default environments of the two projects with the module
  const envOf = (project: string, n: number, name: string, production: boolean, aliases: string[]): DbCicdEnvironment => ({
    id: `cicd-env-${project}-${n}`, projectId: `p-${project}`, name, position: n, production, aliases, hidden: false,
  });
  const environments: DbCicdEnvironment[] = (["web", "mobile"] as const).flatMap((project) => [
    envOf(project, 1, "개발", false, ["dev", "development"]),
    envOf(project, 2, "스테이징", false, ["stg", "staging"]),
    envOf(project, 3, "운영", true, ["prod", "production"]),
  ]);
  const portal = (path: string) => `${GITLAB}/web/portal/-/${path}`;
  const pipeline = (n: number, name: string, commitN: number, ref: string, status: CicdBuildStatus, startedAt: string, finishedAt: string | null): DbCicdPipeline => ({
    id: `cicd-pipe-${9000 + n}`, repositoryId: CICD.repoWeb, externalId: String(9000 + n), name, sha: commits[commitN - 1].sha, ref, status, webUrl: portal(`pipelines/${9000 + n}`),
    startedAt, finishedAt, source: "gitlab", updatedAt: finishedAt ?? startedAt,
  });
  // commit 3 has a failed CI and a good lint: the badge shows the failure (2 workflows); commit 4 is still running
  const pipelines: DbCicdPipeline[] = [
    pipeline(1, "CI", 1, "main", "success", "2026-10-05T02:15:00Z", "2026-10-05T02:22:00Z"),
    pipeline(2, "CI", 2, "main", "success", "2026-10-06T05:50:00Z", "2026-10-06T05:57:00Z"),
    pipeline(3, "CI", 3, "fix/8-login-contrast", "failed", "2026-10-07T01:25:00Z", "2026-10-07T01:31:00Z"),
    pipeline(4, "lint", 3, "fix/8-login-contrast", "success", "2026-10-07T01:25:00Z", "2026-10-07T01:27:00Z"),
    pipeline(5, "CI", 4, "main", "running", "2026-10-08T07:10:00Z", null),
    pipeline(6, "CI", 2, "feature/4-payment-api", "success", "2026-10-06T06:00:00Z", "2026-10-06T06:08:00Z"),
    pipeline(7, "CI", 3, "fix/8-login-contrast", "failed", "2026-10-09T04:35:00Z", "2026-10-09T04:41:00Z"),
  ];
  const [dev, stg] = environments;
  const deployment = (n: number, env: DbCicdEnvironment | null, envName: string, commitN: number, deployedAt: string, inclusion: DbCicdInclusion | null): DbCicdDeployment => ({
    id: `cicd-deploy-${8000 + n}`, repositoryId: CICD.repoWeb, environmentId: env?.id ?? null, environmentName: envName, externalId: String(8000 + n),
    sha: commits[commitN - 1].sha, status: "success", webUrl: portal(`environments/${8000 + n}`), deployedAt, source: "gitlab", inclusion, firstCutoffAt: null,
  });
  // the first deployment of 개발 and 스테이징 brought commits 1 and 2; the next 개발 deployment commit 4; qa-env is no environment of the project
  const deployments: DbCicdDeployment[] = [
    deployment(1, dev, "개발", 2, "2026-10-10T01:00:00Z", "first"),
    deployment(2, dev, "개발", 4, "2026-10-12T02:00:00Z", "compared"),
    deployment(3, stg, "스테이징", 2, "2026-10-11T03:00:00Z", "first"),
    deployment(4, null, "qa-env", 4, "2026-10-13T04:00:00Z", null),
    deployment(5, null, "qa-env", 4, "2026-10-14T04:00:00Z", null),
  ];
  const inEnv = (d: DbCicdDeployment, commitN: number): DbCicdEnvironmentCommit => ({
    environmentId: d.environmentId!, repositoryId: CICD.repoWeb, commitId: `cicd-commit-${commitN}`, deploymentId: d.id,
  });
  const environmentCommits: DbCicdEnvironmentCommit[] = [
    inEnv(deployments[0], 1), inEnv(deployments[0], 2), inEnv(deployments[1], 4), inEnv(deployments[2], 1), inEnv(deployments[2], 2),
  ];
  return { connections, connectionProjects, repositories, commits, mergeRequests, links, environments, pipelines, deployments, environmentCommits };
}
