-- CI/CD 연동 2단계 (docs/superpowers/specs/2026-10-16-cicd-phase2-design.md): 빌드(pipeline)·배포(deployment)와 환경.
-- 배포가 담은 커밋 하나와 이전 배포를 코드 저장 도구의 비교 API로 견줘, 환경마다 "지금 들어 있는 커밋"을 유지한다.

alter table cicd_connections drop constraint cicd_connections_kind_check;
alter table cicd_connections add constraint cicd_connections_kind_check check (kind in ('gitlab', 'github', 'jenkins', 'argocd'));
alter table cicd_connections
  add column tool_url text check (tool_url ~ '^https?://'),                              -- Jenkins·Argo CD 링크 검사용 주소
  add column image_tag_pattern text check (length(image_tag_pattern) between 1 and 200);  -- 없으면 기본 규칙(16진수 7–40자리)

-- 저장소 떼기는 행을 지우지 않는다: 뗀 시각만 남기고 새 웹훅·폴링을 받지 않는다
alter table cicd_repositories add column detached_at timestamptz;
create index cicd_repositories_attached_path_idx on cicd_repositories (lower(full_path)) where detached_at is null;

-- 병합된 MR의 병합(또는 squash) 커밋: MR로만 연결된 작업도 환경 판정에 쓴다
alter table cicd_merge_requests add column merge_commit_sha text check (merge_commit_sha ~ '^[0-9a-f]{7,64}$');

alter table cicd_events drop constraint cicd_events_kind_check;
alter table cicd_events add constraint cicd_events_kind_check check (kind in ('commit', 'merge_request', 'pipeline', 'deployment'));

create table cicd_environments (
  id          uuid primary key default gen_random_uuid(),
  project_id  uuid not null references projects (id) on delete cascade,
  name        text not null check (length(name) between 1 and 50),
  position    int not null,
  production  boolean not null default false,
  aliases     text[] not null default '{}',     -- 소문자로 저장한다(맞출 때 대소문자 무시)
  hidden      boolean not null default false,   -- 배포 기록이 있어 지우지 못하고 숨긴 환경
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create unique index cicd_environments_name_key on cicd_environments (project_id, lower(name));
create trigger cicd_environments_set_updated_at before update on cicd_environments for each row execute function set_updated_at();

create table cicd_pipelines (
  id            uuid primary key default gen_random_uuid(),
  repository_id uuid not null references cicd_repositories (id) on delete cascade,
  external_id   text not null,
  name          text not null default '',     -- 워크플로·파이프라인·작업 이름(배지가 이름마다 최신을 모음)
  sha           text not null check (sha ~ '^[0-9a-f]{7,64}$'),
  ref           text not null default '',
  status        text not null check (status in ('running', 'success', 'failed', 'canceled')),
  web_url       text not null default '',
  started_at    timestamptz,
  finished_at   timestamptz,
  source        text not null check (source in ('gitlab', 'github', 'jenkins')),
  updated_at    timestamptz not null,         -- 도구가 알린 시각: 더 새로운 알림만 상태를 바꾼다
  constraint cicd_pipelines_key unique (repository_id, external_id)
);
create index cicd_pipelines_sha_idx on cicd_pipelines (repository_id, sha, updated_at desc);
create index cicd_pipelines_ref_idx on cicd_pipelines (repository_id, ref, updated_at desc);

create table cicd_deployments (
  id               uuid primary key default gen_random_uuid(),
  repository_id    uuid not null references cicd_repositories (id) on delete cascade,
  environment_id   uuid references cicd_environments (id) on delete set null,   -- 못 맞추면 비움(환경 미지정)
  environment_name text not null,
  external_id      text not null,
  sha              text check (sha ~ '^[0-9a-f]{7,64}$'),
  image            text check (length(image) <= 500),
  status           text not null check (status in ('running', 'success', 'failed')),
  web_url          text not null default '',
  deployed_at      timestamptz not null,
  source           text not null check (source in ('gitlab', 'github', 'jenkins', 'argocd')),
  inclusion        text check (inclusion in ('checking', 'compared', 'partial', 'first', 'failed', 'unknown_commit')),
  attempts         int not null default 0,
  next_attempt_at  timestamptz,
  check_error      text check (length(check_error) <= 100),   -- 'status 401', 'timeout', 'no_token' 같은 짧은 말만
  first_cutoff_at  timestamptz,                               -- 첫 배포가 확인한 가장 오래된 연결 커밋의 시각(200개 상한에 걸렸을 때)
  received_at      timestamptz not null default now(),
  constraint cicd_deployments_key unique (repository_id, external_id)
);
create index cicd_deployments_line_idx on cicd_deployments (repository_id, environment_id, deployed_at) where inclusion = 'checking';
create index cicd_deployments_environment_idx on cicd_deployments (environment_id);
create index cicd_deployments_unassigned_idx on cicd_deployments (repository_id, lower(environment_name)) where environment_id is null;

create table cicd_deployment_commits (   -- 배포로 새로 들어간 커밋(3단계 릴리스 노트가 씀)
  deployment_id uuid not null references cicd_deployments (id) on delete cascade,
  commit_id     uuid not null references cicd_commits (id) on delete cascade,
  primary key (deployment_id, commit_id)
);
create index cicd_deployment_commits_commit_idx on cicd_deployment_commits (commit_id);

create table cicd_environment_commits (  -- 지금 각 환경에 들어 있는 커밋. 되돌리기 배포가 빼면 지운다
  environment_id uuid not null references cicd_environments (id) on delete cascade,
  repository_id  uuid not null references cicd_repositories (id) on delete cascade,
  commit_id      uuid not null references cicd_commits (id) on delete cascade,
  deployment_id  uuid not null references cicd_deployments (id) on delete cascade,   -- 들여온 배포
  primary key (environment_id, commit_id)
);
create index cicd_environment_commits_commit_idx on cicd_environment_commits (commit_id);
create index cicd_environment_commits_repository_idx on cicd_environment_commits (repository_id);
create index cicd_environment_commits_deployment_idx on cicd_environment_commits (deployment_id);

create table cicd_environment_heads (    -- 저장소·환경마다 지금 배포된 커밋. 배포 반영의 잠금 단위
  repository_id  uuid not null references cicd_repositories (id) on delete cascade,
  environment_id uuid not null references cicd_environments (id) on delete cascade,
  sha            text not null,
  deployment_id  uuid not null references cicd_deployments (id) on delete cascade,
  deployed_at    timestamptz not null,
  primary key (repository_id, environment_id)
);
create index cicd_environment_heads_environment_idx on cicd_environment_heads (environment_id);
create index cicd_environment_heads_deployment_idx on cicd_environment_heads (deployment_id);

create table cicd_first_deploy_checks (  -- 첫 배포가 확인할 연결된 커밋(최대 200개)
  deployment_id   uuid not null references cicd_deployments (id) on delete cascade,
  commit_id       uuid not null references cicd_commits (id) on delete cascade,
  result          text check (result in ('in', 'out', 'error')),
  attempts        int not null default 0,
  next_attempt_at timestamptz,             -- 1·2·4·8분 간격으로 다시 시도(5번째 실패에 error)
  primary key (deployment_id, commit_id)
);
create index cicd_first_deploy_checks_open_idx on cicd_first_deploy_checks (deployment_id) where result is null;
create index cicd_first_deploy_checks_commit_idx on cicd_first_deploy_checks (commit_id);

-- 이미 모듈 cicd를 쓰는 프로젝트의 기본 환경(새 개발 DB에서는 V1010이 뒤에 모듈을 켜므로 V1012가 넣는다)
insert into cicd_environments (project_id, name, position, production, aliases)
select p.id, e.name, e.position, e.production, e.aliases
  from projects p
  cross join (values ('개발', 1, false, array['dev', 'development']),
                     ('스테이징', 2, false, array['stg', 'staging']),
                     ('운영', 3, true, array['prod', 'production'])) as e(name, position, production, aliases)
 where 'cicd' = any (p.enabled_modules)
   and not exists (select 1 from cicd_environments x where x.project_id = p.id);
