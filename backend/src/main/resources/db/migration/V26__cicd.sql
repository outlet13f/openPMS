-- CI/CD 연동 1단계 (docs/superpowers/specs/2026-10-15-cicd-design.md): GitLab·GitHub의 커밋과 MR/PR을 받아
-- 저장소가 붙은 프로젝트(와 하위 프로젝트)의 작업 패키지에 #번호로 잇는다. CMDB의 CI(구성 항목)와 다른 말이라 이름은 cicd_.

alter table projects drop constraint projects_known_modules;
alter table projects add constraint projects_known_modules check (
  enabled_modules <@ array['work_packages', 'gantt', 'boards', 'calendar', 'team_planner', 'time_tracking', 'cicd']::text[]);

-- 도구 연동. 토큰·웹훅 비밀은 앱이 OPENPMS_SECRET_KEY로 AES-GCM 암호화한 값(cicd/SecretBox)이다.
create table cicd_connections (
  id                 uuid primary key default gen_random_uuid(),
  kind               text not null check (kind in ('gitlab', 'github')),
  name               text not null unique check (length(name) between 1 and 100),
  base_url           text not null check (base_url ~ '^https?://'),
  api_token_enc      text,          -- 없으면 폴링·저장소 검색을 하지 않는다
  api_token_hint     text,          -- 끝 4자리
  webhook_secret_enc text,          -- 없으면 웹훅을 받지 않는다
  webhook_secret_hint text,
  hook_key           text not null unique,   -- 웹훅 주소의 난수 (/api/v1/hooks/cicd/{hook_key})
  active             boolean not null default true,
  created_by         uuid references users (id) on delete set null,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
create index cicd_connections_created_by_idx on cicd_connections (created_by);
create trigger cicd_connections_set_updated_at before update on cicd_connections for each row execute function set_updated_at();

create table cicd_repositories (
  id             uuid primary key default gen_random_uuid(),
  connection_id  uuid not null references cicd_connections (id) on delete cascade,
  external_id    text not null,            -- GitLab 프로젝트 id, GitHub 저장소 id
  full_path      text not null,            -- group/name, owner/name
  web_url        text not null,
  project_id     uuid not null references projects (id) on delete cascade,
  poll_cursor    timestamptz,              -- 이 시각 뒤에 바뀐 것을 폴링한다
  last_polled_at timestamptz,
  created_at     timestamptz not null default now(),
  constraint cicd_repositories_key unique (connection_id, external_id)
);
create index cicd_repositories_project_idx on cicd_repositories (project_id);

create table cicd_events (
  id            uuid primary key default gen_random_uuid(),
  connection_id uuid not null references cicd_connections (id) on delete cascade,
  repository_id uuid references cicd_repositories (id) on delete set null,
  kind          text not null check (kind in ('commit', 'merge_request')),
  delivery_key  text not null,   -- 도구가 준 전달 id, 또는 kind:저장소:sha / kind:저장소:iid:updated_at
  payload       jsonb not null,  -- 정규화한 필드만(CicdEvent)
  via           text not null check (via in ('webhook', 'poll')),
  status        text not null default 'pending' check (status in ('pending', 'done', 'failed')),
  error         text,
  received_at   timestamptz not null default now(),
  processed_at  timestamptz,
  constraint cicd_events_key unique (connection_id, delivery_key)
);
create index cicd_events_pending_idx on cicd_events (received_at) where status = 'pending';
create index cicd_events_repository_idx on cicd_events (repository_id);

create table cicd_commits (
  id            uuid primary key default gen_random_uuid(),
  repository_id uuid not null references cicd_repositories (id) on delete cascade,
  sha           text not null check (sha ~ '^[0-9a-f]{7,64}$'),
  title         text not null,
  author_name   text not null default '',
  author_email  text not null default '',
  user_id       uuid references users (id) on delete set null,
  committed_at  timestamptz not null,
  web_url       text not null,
  constraint cicd_commits_key unique (repository_id, sha)
);
create index cicd_commits_user_idx on cicd_commits (user_id);

create table cicd_merge_requests (
  id            uuid primary key default gen_random_uuid(),
  repository_id uuid not null references cicd_repositories (id) on delete cascade,
  iid           bigint not null,
  title         text not null,
  state         text not null check (state in ('open', 'draft', 'merged', 'closed')),
  source_branch text not null default '',
  target_branch text not null default '',
  author_name   text not null default '',
  user_id       uuid references users (id) on delete set null,
  merged_at     timestamptz,
  updated_at    timestamptz not null,
  web_url       text not null,
  constraint cicd_merge_requests_key unique (repository_id, iid)
);
create index cicd_merge_requests_user_idx on cicd_merge_requests (user_id);

create table work_package_cicd_links (
  id                uuid primary key default gen_random_uuid(),
  work_package_id   uuid not null references work_packages (id) on delete cascade,
  commit_id         uuid references cicd_commits (id) on delete cascade,
  merge_request_id  uuid references cicd_merge_requests (id) on delete cascade,
  source            text not null check (source in ('message', 'branch', 'title', 'manual')),
  created_by        uuid references users (id) on delete set null,
  created_at        timestamptz not null default now(),
  dismissed_by      uuid references users (id) on delete set null,
  dismissed_at      timestamptz,   -- 끊은 연결: 행을 남겨 다시 생기지 않게 한다
  constraint work_package_cicd_links_one_target check ((commit_id is null) <> (merge_request_id is null))
);
create unique index work_package_cicd_links_commit_key on work_package_cicd_links (work_package_id, commit_id) where commit_id is not null;
create unique index work_package_cicd_links_mr_key on work_package_cicd_links (work_package_id, merge_request_id) where merge_request_id is not null;
create index work_package_cicd_links_commit_idx on work_package_cicd_links (commit_id);
create index work_package_cicd_links_mr_idx on work_package_cicd_links (merge_request_id);
create index work_package_cicd_links_created_by_idx on work_package_cicd_links (created_by);
create index work_package_cicd_links_dismissed_by_idx on work_package_cicd_links (dismissed_by);

-- 역할 -----------------------------------------------------------------------
-- 이미 설치된 곳: 이름이 아니라 가진 권한으로 더한다. 내장 역할(비멤버)은 건드리지 않는다.
update roles set permissions = permissions || array['cicd.view']
  where scope = 'project' and builtin is null and 'wp.view' = any (permissions) and not ('cicd.view' = any (permissions));
update roles set permissions = permissions || array['cicd.link']
  where scope = 'project' and builtin is null and 'wp.edit' = any (permissions) and not ('cicd.link' = any (permissions));
update roles set permissions = permissions || array['cicd.manage']
  where scope = 'project' and builtin is null and 'project.manage_members' = any (permissions) and not ('cicd.manage' = any (permissions));

-- 이름은 유일하다: 같은 이름을 이미 쓰는 설치는 그 행 뒤에 ' (기존)'을 붙인다
update roles set name = left(name, 95) || ' (기존)'
  where name in ('개발 리더', 'CI/CD 연동 관리자') and id not in ('8e9c194d-fcdf-5c47-b081-ab2b2b61c89a', 'bae8d8f2-850d-5250-9c69-25b1631be394');

-- 개발 리더 = 멤버 + cicd.manage (멤버 역할이 없는 설치는 cicd 권한과 작업 보기·편집만)
insert into roles (id, name, scope, builtin, permissions, position)
select '8e9c194d-fcdf-5c47-b081-ab2b2b61c89a', '개발 리더', 'project', null,
       coalesce((select array(select distinct unnest(permissions || array['cicd.view', 'cicd.link', 'cicd.manage']))
                 from roles where id = '6d4333fb-e975-5213-8ebc-552cca26e1d4'),
                array['wp.view', 'wp.edit', 'cicd.view', 'cicd.link', 'cicd.manage']),
       coalesce((select max(position) + 1 from roles where scope = 'project'), 1);

insert into roles (id, name, scope, builtin, permissions, position)
select 'bae8d8f2-850d-5250-9c69-25b1631be394', 'CI/CD 연동 관리자', 'global', null, array['global.manage_cicd'],
       coalesce((select max(position) + 1 from roles where scope = 'global'), 1);
