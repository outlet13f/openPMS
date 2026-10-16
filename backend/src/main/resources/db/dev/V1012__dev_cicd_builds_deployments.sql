-- 개발 데이터: CI/CD 연동 2단계의 빌드·배포·환경 (V28 뒤, V1010 저장소·커밋 뒤).
-- 새 개발 DB에서는 V28이 V1010(모듈 cicd를 켬)보다 먼저 돌아 기본 환경을 못 넣었으므로 여기서 web-platform·mobile-app에 넣는다.
-- 이미 설치된 개발 DB(V28이 환경을 넣음)에서는 그대로 두고, 아래 행은 이름으로 환경을 찾는다. 해당 행이 없는 DB에서는 아무것도 넣지 않는다.
-- 결과: web-platform #4 개발 ✓ · 스테이징 ✓ · 운영 –, #8(커밋 3) 개발 –, #11(커밋 4) 개발 ✓ · 스테이징 –.

-- 기본 환경 (프로젝트에 환경이 하나도 없을 때만)
insert into cicd_environments (id, project_id, name, position, production, aliases)
select e.id::uuid, p.id, e.name, e.position, e.production, e.aliases
  from projects p
  join (values
    ('935daeb0-d1aa-57f4-88e1-0050b30413ac', '0d1e2f30-0001-4d00-8000-000000004001', '개발', 1, false, array['dev', 'development']),
    ('935daeb0-d1aa-57f4-88e1-0050b30413ac', '0d1e2f30-0001-4d00-8000-000000004002', '스테이징', 2, false, array['stg', 'staging']),
    ('935daeb0-d1aa-57f4-88e1-0050b30413ac', '0d1e2f30-0001-4d00-8000-000000004003', '운영', 3, true, array['prod', 'production']),
    ('09e67323-1a1d-5ccd-854b-78e0628e38c0', '0d1e2f30-0001-4d00-8000-000000004011', '개발', 1, false, array['dev', 'development']),
    ('09e67323-1a1d-5ccd-854b-78e0628e38c0', '0d1e2f30-0001-4d00-8000-000000004012', '스테이징', 2, false, array['stg', 'staging']),
    ('09e67323-1a1d-5ccd-854b-78e0628e38c0', '0d1e2f30-0001-4d00-8000-000000004013', '운영', 3, true, array['prod', 'production'])
  ) as e(project_id, id, name, position, production, aliases) on e.project_id = p.id::text
 where 'cicd' = any (p.enabled_modules)
   and not exists (select 1 from cicd_environments x where x.project_id = p.id)
on conflict do nothing;

-- 빌드: web/portal 커밋 1–4와 MR 12·13의 브랜치
insert into cicd_pipelines (id, repository_id, external_id, name, sha, ref, status, web_url, started_at, finished_at, source, updated_at)
select v.id::uuid, r.id, v.external_id, v.name, c.sha, v.ref, v.status,
       'https://gitlab.example.invalid/web/portal/-/pipelines/' || v.external_id,
       v.started_at::timestamptz, v.finished_at::timestamptz, 'gitlab', coalesce(v.finished_at, v.started_at)::timestamptz
  from cicd_repositories r
  join (values
    ('0f1a2b3c-0001-4f00-8000-000000005001', '9001', 'CI',   '0a1b2c3d-0001-4a00-8000-000000001001', 'main', 'success', '2026-10-05T02:15:00Z', '2026-10-05T02:22:00Z'),
    ('0f1a2b3c-0002-4f00-8000-000000005002', '9002', 'CI',   '0a1b2c3d-0002-4a00-8000-000000001002', 'main', 'success', '2026-10-06T05:50:00Z', '2026-10-06T05:57:00Z'),
    ('0f1a2b3c-0003-4f00-8000-000000005003', '9003', 'CI',   '0a1b2c3d-0003-4a00-8000-000000001003', 'fix/8-login-contrast', 'failed', '2026-10-07T01:25:00Z', '2026-10-07T01:31:00Z'),
    ('0f1a2b3c-0004-4f00-8000-000000005004', '9004', 'lint', '0a1b2c3d-0003-4a00-8000-000000001003', 'fix/8-login-contrast', 'success', '2026-10-07T01:25:00Z', '2026-10-07T01:27:00Z'),
    ('0f1a2b3c-0005-4f00-8000-000000005005', '9005', 'CI',   '0a1b2c3d-0004-4a00-8000-000000001004', 'main', 'running', '2026-10-08T07:10:00Z', null)
  ) as v(id, external_id, name, commit_id, ref, status, started_at, finished_at) on true
  join cicd_commits c on c.id = v.commit_id::uuid and c.repository_id = r.id
 where r.id = 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea'
on conflict do nothing;

-- MR 12·13 브랜치의 빌드: 브랜치 이름으로 맞추므로 sha는 MR의 마지막 커밋(결제: 커밋 2, 대비: 커밋 3)
insert into cicd_pipelines (id, repository_id, external_id, name, sha, ref, status, web_url, started_at, finished_at, source, updated_at)
select v.id::uuid, r.id, v.external_id, 'CI', c.sha, v.ref, v.status,
       'https://gitlab.example.invalid/web/portal/-/pipelines/' || v.external_id,
       v.started_at::timestamptz, v.finished_at::timestamptz, 'gitlab', v.finished_at::timestamptz
  from cicd_repositories r
  join (values
    ('0f1a2b3c-0006-4f00-8000-000000005006', '9006', 'feature/4-payment-api', '0a1b2c3d-0002-4a00-8000-000000001002', 'success', '2026-10-06T06:00:00Z', '2026-10-06T06:08:00Z'),
    ('0f1a2b3c-0007-4f00-8000-000000005007', '9007', 'fix/8-login-contrast',   '0a1b2c3d-0003-4a00-8000-000000001003', 'failed',  '2026-10-09T04:35:00Z', '2026-10-09T04:41:00Z')
  ) as v(id, external_id, ref, commit_id, status, started_at, finished_at) on true
  join cicd_commits c on c.id = v.commit_id::uuid and c.repository_id = r.id
 where r.id = 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea'
on conflict do nothing;

-- MR 12는 병합됨: 병합 커밋은 커밋 2
update cicd_merge_requests m set merge_commit_sha = c.sha
  from cicd_commits c
 where m.id = '0b1c2d3e-0001-4b00-8000-000000002001' and c.id = '0a1b2c3d-0002-4a00-8000-000000001002'
   and m.merge_commit_sha is null;

-- 배포: 개발 첫 배포(커밋 2) → 개발 다음 배포(커밋 4), 스테이징 첫 배포(커밋 2), 환경 미지정 qa-env 두 건(커밋 4)
insert into cicd_deployments (id, repository_id, environment_id, environment_name, external_id, sha, status, web_url, deployed_at, source, inclusion, attempts, received_at)
select v.id::uuid, r.id,
       (select e.id from cicd_environments e where e.project_id = r.project_id and lower(e.name) = lower(v.env_name)),
       v.env_name, v.external_id, c.sha, 'success',
       'https://gitlab.example.invalid/web/portal/-/environments/' || v.external_id,
       v.deployed_at::timestamptz, 'gitlab', v.inclusion, 0, v.deployed_at::timestamptz
  from cicd_repositories r
  join (values
    ('0e1f2a3b-0001-4e00-8000-000000006001', '개발',     '8001', '0a1b2c3d-0002-4a00-8000-000000001002', '2026-10-10T01:00:00Z', 'first'),
    ('0e1f2a3b-0002-4e00-8000-000000006002', '개발',     '8002', '0a1b2c3d-0004-4a00-8000-000000001004', '2026-10-12T02:00:00Z', 'compared'),
    ('0e1f2a3b-0003-4e00-8000-000000006003', '스테이징', '8003', '0a1b2c3d-0002-4a00-8000-000000001002', '2026-10-11T03:00:00Z', 'first'),
    ('0e1f2a3b-0004-4e00-8000-000000006004', 'qa-env',   '8004', '0a1b2c3d-0004-4a00-8000-000000001004', '2026-10-13T04:00:00Z', null),
    ('0e1f2a3b-0005-4e00-8000-000000006005', 'qa-env',   '8005', '0a1b2c3d-0004-4a00-8000-000000001004', '2026-10-14T04:00:00Z', null)
  ) as v(id, env_name, external_id, commit_id, deployed_at, inclusion) on true
  join cicd_commits c on c.id = v.commit_id::uuid and c.repository_id = r.id
 where r.id = 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea'
   and (v.env_name = 'qa-env' or exists (select 1 from cicd_environments e where e.project_id = r.project_id and lower(e.name) = lower(v.env_name)))
on conflict do nothing;

-- 배포가 새로 들여온 커밋: 개발 첫 배포 = 커밋 1·2, 개발 다음 배포 = 커밋 4, 스테이징 첫 배포 = 커밋 1·2
insert into cicd_deployment_commits (deployment_id, commit_id)
select v.deployment_id::uuid, v.commit_id::uuid
  from (values
    ('0e1f2a3b-0001-4e00-8000-000000006001', '0a1b2c3d-0001-4a00-8000-000000001001'),
    ('0e1f2a3b-0001-4e00-8000-000000006001', '0a1b2c3d-0002-4a00-8000-000000001002'),
    ('0e1f2a3b-0002-4e00-8000-000000006002', '0a1b2c3d-0004-4a00-8000-000000001004'),
    ('0e1f2a3b-0003-4e00-8000-000000006003', '0a1b2c3d-0001-4a00-8000-000000001001'),
    ('0e1f2a3b-0003-4e00-8000-000000006003', '0a1b2c3d-0002-4a00-8000-000000001002')
  ) as v(deployment_id, commit_id)
 where exists (select 1 from cicd_deployments d where d.id = v.deployment_id::uuid)
   and exists (select 1 from cicd_commits c where c.id = v.commit_id::uuid)
on conflict do nothing;

-- 지금 환경에 들어 있는 커밋: 개발 = 1·2·4, 스테이징 = 1·2
insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id)
select d.environment_id, d.repository_id, v.commit_id::uuid, d.id
  from (values
    ('0e1f2a3b-0001-4e00-8000-000000006001', '0a1b2c3d-0001-4a00-8000-000000001001'),
    ('0e1f2a3b-0001-4e00-8000-000000006001', '0a1b2c3d-0002-4a00-8000-000000001002'),
    ('0e1f2a3b-0002-4e00-8000-000000006002', '0a1b2c3d-0004-4a00-8000-000000001004'),
    ('0e1f2a3b-0003-4e00-8000-000000006003', '0a1b2c3d-0001-4a00-8000-000000001001'),
    ('0e1f2a3b-0003-4e00-8000-000000006003', '0a1b2c3d-0002-4a00-8000-000000001002')
  ) as v(deployment_id, commit_id)
  join cicd_deployments d on d.id = v.deployment_id::uuid and d.environment_id is not null
  join cicd_commits c on c.id = v.commit_id::uuid
on conflict do nothing;

-- 머리: 개발 = 개발 다음 배포(커밋 4), 스테이징 = 스테이징 첫 배포(커밋 2)
insert into cicd_environment_heads (repository_id, environment_id, sha, deployment_id, deployed_at)
select d.repository_id, d.environment_id, d.sha, d.id, d.deployed_at
  from cicd_deployments d
 where d.id in ('0e1f2a3b-0002-4e00-8000-000000006002', '0e1f2a3b-0003-4e00-8000-000000006003')
   and d.environment_id is not null
on conflict do nothing;
