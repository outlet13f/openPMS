-- CI/CD 연동 3단계 (docs/superpowers/specs/2026-10-20-cicd-phase3-release-design.md): 릴리스 검증 보드와 릴리스 기록.

-- 환경에 커밋이 들어온 시각 = 들여온 배포의 배포 시각. 다시 확인된 커밋(on conflict do update)은 시각을 옮기지 않는다.
-- V1012와 2단계 코드는 이 열을 모르고 넣으므로 트리거가 채운다(새 개발 DB에서는 V1012가 이 마이그레이션 뒤에 돈다).
alter table cicd_environment_commits add column added_at timestamptz;
update cicd_environment_commits ec set added_at = d.deployed_at from cicd_deployments d where d.id = ec.deployment_id;
alter table cicd_environment_commits alter column added_at set not null;
create function cicd_environment_commits_added_at() returns trigger
language plpgsql as $$
begin
  if new.added_at is null then
    select d.deployed_at into new.added_at from cicd_deployments d where d.id = new.deployment_id;
  end if;
  return new;
end
$$;
create trigger cicd_environment_commits_added_at before insert on cicd_environment_commits
  for each row execute function cicd_environment_commits_added_at();
create index cicd_environment_commits_added_idx on cicd_environment_commits (environment_id, repository_id, added_at);

alter table notifications drop constraint notifications_reason_check;
alter table notifications add constraint notifications_reason_check
  check (reason in ('mentioned', 'assigned', 'responsible', 'watched', 'shared', 'created', 'commented', 'status_changed',
                    'priority_changed', 'dates_changed', 'date_alert_start', 'date_alert_due', 'date_alert_overdue',
                    'verification_failed'));

-- 검증 결과: 추가만 하는 이력. 현재 결과 = 작업 × 원본 환경마다 verified_at이 가장 늦은 행
create table cicd_verifications (
  id              uuid primary key default gen_random_uuid(),
  work_package_id uuid not null references work_packages (id) on delete cascade,
  environment_id  uuid not null references cicd_environments (id) on delete cascade,
  result          text not null check (result in ('passed', 'failed')),
  reason          text not null default '',
  verified_by     uuid references users (id) on delete set null,
  verified_at     timestamptz not null default now(),
  basis_at        timestamptz not null,   -- 그때 그 작업의 커밋이 원본 환경에 가장 늦게 들어온 시각(재검증 판단 기준)
  constraint cicd_verifications_reason check ((result = 'passed' and reason = '') or (result = 'failed' and length(reason) between 1 and 2000))
);
create index cicd_verifications_current_idx on cicd_verifications (work_package_id, environment_id, verified_at desc);
create index cicd_verifications_environment_idx on cicd_verifications (environment_id);
create index cicd_verifications_user_idx on cicd_verifications (verified_by);

-- "이미 운영에 있음"으로 보드(원본 → 대상)에서 뺀 작업. 새 커밋이 원본에 들어오면(added_at > added_before) 다시 보인다
create table cicd_board_exclusions (
  work_package_id       uuid not null references work_packages (id) on delete cascade,
  environment_id        uuid not null references cicd_environments (id) on delete cascade,
  target_environment_id uuid not null references cicd_environments (id) on delete cascade,
  excluded_by           uuid references users (id) on delete set null,
  excluded_at           timestamptz not null default now(),
  added_before          timestamptz not null,
  primary key (work_package_id, environment_id, target_environment_id)
);
create index cicd_board_exclusions_board_idx on cicd_board_exclusions (environment_id, target_environment_id);
create index cicd_board_exclusions_target_idx on cicd_board_exclusions (target_environment_id);
create index cicd_board_exclusions_user_idx on cicd_board_exclusions (excluded_by);

-- 릴리스 기록: 대상 환경(production)에 커밋을 들여온 배포 하나 = 기록 하나(읽기 전용 스냅숏)
create table cicd_releases (
  id                    uuid primary key default gen_random_uuid(),
  project_id            uuid not null references projects (id) on delete cascade,
  environment_id        uuid not null references cicd_environments (id) on delete cascade,
  source_environment_id uuid references cicd_environments (id) on delete set null,   -- 기록 시점의 기본 원본(없으면 null)
  deployment_id         uuid not null references cicd_deployments (id) on delete cascade,
  repository_id         uuid not null references cicd_repositories (id) on delete cascade,
  deployed_at           timestamptz not null,
  created_at            timestamptz not null default now(),
  partial               boolean not null default false,   -- 비교가 한쪽 1,000개 상한에 걸림
  content_failed        boolean not null default false,   -- 확인 실패: 들어간 작업을 알 수 없음(작업 행 없음)
  constraint cicd_releases_deployment_key unique (deployment_id)
);
create index cicd_releases_project_idx on cicd_releases (project_id, deployed_at desc);
create index cicd_releases_environment_idx on cicd_releases (environment_id);
create index cicd_releases_source_idx on cicd_releases (source_environment_id);
create index cicd_releases_repository_idx on cicd_releases (repository_id);

create table cicd_release_items (
  id              uuid primary key default gen_random_uuid(),
  release_id      uuid not null references cicd_releases (id) on delete cascade,
  work_package_id uuid references work_packages (id) on delete set null,
  number          bigint not null,    -- 기록 시점의 작업 번호·제목
  subject         text not null,
  verification_id uuid references cicd_verifications (id) on delete set null,
  warning         text check (warning in ('unverified', 'failed', 'stale')),
  constraint cicd_release_items_key unique (release_id, work_package_id)
);
create index cicd_release_items_work_package_idx on cicd_release_items (work_package_id);
create index cicd_release_items_verification_idx on cicd_release_items (verification_id);

-- 권한: 이미 설치된 곳은 이름이 아니라 가진 권한으로 더한다(V26 방식). 저장소 관리(cicd.manage)를 가진 역할 = 개발 리더·프로젝트 관리자 계열
update roles set permissions = permissions || array['release.verify']
  where scope = 'project' and builtin is null and 'cicd.manage' = any (permissions) and not ('release.verify' = any (permissions));

update roles set name = left(name, 95) || ' (기존)' where name = 'QA' and id <> '55ecb595-b468-52b0-bfbd-52f40d6333a4';
insert into roles (id, name, scope, builtin, permissions, position)
select '55ecb595-b468-52b0-bfbd-52f40d6333a4', 'QA', 'project', null,
       array['project.view', 'wp.view', 'wp.comment', 'wp.edit_own_comments', 'cicd.view', 'release.verify'],
       coalesce((select max(position) + 1 from roles where scope = 'project'), 1);
