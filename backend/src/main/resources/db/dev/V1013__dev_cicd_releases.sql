-- 개발 데이터: CI/CD 연동 3단계의 릴리스 검증 보드와 릴리스 기록 (V29 뒤, V1010·V1012 저장소·커밋·배포 뒤).
-- 이미 다른 데이터가 있는 개발 DB에서도 깨지지 않게 모든 INSERT는 where exists·on conflict do nothing. 환경은 이름으로 찾는다(V1012와 같은 방식).
-- 보드(web-platform, 스테이징 → 운영, 버전 "2026 3분기", 오늘 2026-10-20):
--   검증 대기 #11, 재검증 필요 #1, 실패 #4, 통과 #3·#5. 빠짐 #6(코드 연결 없음) 1, 끼어듦 #11 1, 연결 없는 커밋 1.
--   기준 시각 = 운영 첫 배포 10-13T05:00Z라 커밋 1·2·12(그 앞에 스테이징에 들어옴)는 보드에 없다. 기록 2개 중 하나에 경고 1.

-- QA 사용자 윤서아: web-platform 멤버, 역할 QA (비밀번호는 V1000과 같은 openpms!)
insert into users (id, email, name, password_hash, status, is_admin, created_at, updated_at)
select 'fe75bf4d-827d-553e-9e4a-fdb703b4a272', 'seoa.yoon@openpms.local', '윤서아', '{bcrypt}' || crypt('openpms!', gen_salt('bf', 10)), 'active', false,
       '2026-09-14T00:30:00Z', '2026-09-14T00:30:00Z'
 where exists (select 1 from projects where id = '935daeb0-d1aa-57f4-88e1-0050b30413ac')
   and not exists (select 1 from users where email = 'seoa.yoon@openpms.local')
on conflict do nothing;

insert into members (id, project_id, user_id, created_at, updated_at)
select 'f6b89dcf-f7cc-56ea-841f-58b9d1e40981', '935daeb0-d1aa-57f4-88e1-0050b30413ac', 'fe75bf4d-827d-553e-9e4a-fdb703b4a272',
       '2026-09-14T00:30:00Z', '2026-09-14T00:30:00Z'
 where exists (select 1 from users where id = 'fe75bf4d-827d-553e-9e4a-fdb703b4a272')
   and exists (select 1 from projects where id = '935daeb0-d1aa-57f4-88e1-0050b30413ac')
on conflict do nothing;

insert into member_roles (member_id, role_id)
select 'f6b89dcf-f7cc-56ea-841f-58b9d1e40981', '55ecb595-b468-52b0-bfbd-52f40d6333a4'
 where exists (select 1 from members where id = 'f6b89dcf-f7cc-56ea-841f-58b9d1e40981')
   and exists (select 1 from roles where id = '55ecb595-b468-52b0-bfbd-52f40d6333a4')
on conflict do nothing;

-- 커밋 5–13 (작성자 박준호, 시각은 그 커밋이 스테이징에 들어가기 30분 전)
insert into cicd_commits (id, repository_id, sha, title, author_name, author_email, user_id, committed_at, web_url)
select v.id::uuid, r.id, v.sha, v.title, '박준호', 'junho.park@openpms.local', 'c48ce4ad-3a64-55ab-a95c-2902395dbb46', v.committed_at::timestamptz,
       'https://gitlab.example.invalid/web/portal/-/commit/' || v.sha
  from cicd_repositories r
  join (values
    ('0a1b2c3d-0005-4a00-8000-000000001005', '075c202cfe6aaf5c29b6fc5d8af2a0e7616e3f14', '화면 설계 검토 반영 (#3)',           '2026-10-16T01:30:00Z'),
    ('0a1b2c3d-0006-4a00-8000-000000001006', 'ad3c86a35f969db1685b506b126023e38d5039f7', '릴리스 노트 초안 (#5)',             '2026-10-16T01:30:00Z'),
    ('0a1b2c3d-0007-4a00-8000-000000001007', '6b4a7a20440c37d8434f99ecb968540a97730238', '결제 실패 문구 정리 (#4)',          '2026-10-16T01:30:00Z'),
    ('0a1b2c3d-0008-4a00-8000-000000001008', 'f3139102138a4ea4a56d34020f2d73f422660598', '릴리스 준비 체크리스트 (#1)',      '2026-10-16T01:30:00Z'),
    ('0a1b2c3d-0009-4a00-8000-000000001009', 'd1ec4a55ddc65350f2e03c6cb8fa660c45cedcef', '릴리스 준비 체크리스트 보완 (#1)', '2026-10-18T02:30:00Z'),
    ('0a1b2c3d-0010-4a00-8000-000000001010', 'e00f05f4af446315970b6178a94e39c10f950bf9', '검색 정렬 기준 고침 (#11)',        '2026-10-18T02:30:00Z'),
    ('0a1b2c3d-0011-4a00-8000-000000001011', '593c6088e7451233ae71aeb491ae654bfccb521b', '의존성 정리',                      '2026-10-18T02:30:00Z'),
    ('0a1b2c3d-0012-4a00-8000-000000001012', 'c09fa833f61e5e3c35dd77b13d277e4ddb51e29b', '배포 스크립트 정리 (#13)',         '2026-10-13T03:30:00Z'),
    ('0a1b2c3d-0013-4a00-8000-000000001013', 'a0b874060599719d7e0894ad5dce099e375b53e4', '로그인 대비 재수정 (#8)',          '2026-10-14T03:30:00Z')
  ) as v(id, sha, title, committed_at) on true
 where r.id = 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea'
on conflict do nothing;

-- 커밋 ↔ 작업 연결(커밋 11은 연결 없음)
insert into work_package_cicd_links (id, work_package_id, commit_id, merge_request_id, source, created_by, created_at, dismissed_by, dismissed_at)
select v.id::uuid, w.id, c.id, null, 'message', null, c.committed_at + interval '1 minute', null, null
  from (values
    ('0c1d2e3f-0008-4c00-8000-000000003008', 5,  '0a1b2c3d-0005-4a00-8000-000000001005', 3),
    ('0c1d2e3f-0009-4c00-8000-000000003009', 6,  '0a1b2c3d-0006-4a00-8000-000000001006', 5),
    ('0c1d2e3f-0010-4c00-8000-000000003010', 7,  '0a1b2c3d-0007-4a00-8000-000000001007', 4),
    ('0c1d2e3f-0011-4c00-8000-000000003011', 8,  '0a1b2c3d-0008-4a00-8000-000000001008', 1),
    ('0c1d2e3f-0012-4c00-8000-000000003012', 9,  '0a1b2c3d-0009-4a00-8000-000000001009', 1),
    ('0c1d2e3f-0013-4c00-8000-000000003013', 10, '0a1b2c3d-0010-4a00-8000-000000001010', 11),
    ('0c1d2e3f-0014-4c00-8000-000000003014', 12, '0a1b2c3d-0012-4a00-8000-000000001012', 13),
    ('0c1d2e3f-0015-4c00-8000-000000003015', 13, '0a1b2c3d-0013-4a00-8000-000000001013', 8)
  ) as v(id, n, commit_id, wp_number)
  join cicd_commits c on c.id = v.commit_id::uuid
  join work_packages w on w.project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac' and w.number = v.wp_number
on conflict do nothing;

-- 배포: 스테이징·운영 (환경은 이름으로)
insert into cicd_deployments (id, repository_id, environment_id, environment_name, external_id, sha, status, web_url, deployed_at, source, inclusion, attempts, received_at)
select v.id::uuid, r.id,
       (select e.id from cicd_environments e where e.project_id = r.project_id and lower(e.name) = lower(v.env_name)),
       v.env_name, v.external_id, c.sha, 'success',
       'https://gitlab.example.invalid/web/portal/-/environments/' || v.external_id,
       v.deployed_at::timestamptz, 'gitlab', v.inclusion, 0, v.deployed_at::timestamptz
  from cicd_repositories r
  join (values
    ('0e1f2a3b-0006-4e00-8000-000000006006', '스테이징', '8006', '0a1b2c3d-0012-4a00-8000-000000001012', '2026-10-13T04:00:00Z', 'compared'),
    ('0e1f2a3b-0007-4e00-8000-000000006007', '운영',     '8007', '0a1b2c3d-0002-4a00-8000-000000001002', '2026-10-13T05:00:00Z', 'first'),
    ('0e1f2a3b-0008-4e00-8000-000000006008', '스테이징', '8008', '0a1b2c3d-0013-4a00-8000-000000001013', '2026-10-14T04:00:00Z', 'compared'),
    ('0e1f2a3b-0009-4e00-8000-000000006009', '운영',     '8009', '0a1b2c3d-0012-4a00-8000-000000001012', '2026-10-14T05:00:00Z', 'compared'),
    ('0e1f2a3b-0010-4e00-8000-000000006010', '운영',     '8010', '0a1b2c3d-0013-4a00-8000-000000001013', '2026-10-15T05:00:00Z', 'compared'),
    ('0e1f2a3b-0011-4e00-8000-000000006011', '스테이징', '8011', '0a1b2c3d-0008-4a00-8000-000000001008', '2026-10-16T02:00:00Z', 'compared'),
    ('0e1f2a3b-0012-4e00-8000-000000006012', '스테이징', '8012', '0a1b2c3d-0011-4a00-8000-000000001011', '2026-10-18T03:00:00Z', 'compared')
  ) as v(id, env_name, external_id, commit_id, deployed_at, inclusion) on true
  join cicd_commits c on c.id = v.commit_id::uuid and c.repository_id = r.id
 where r.id = 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea'
   and exists (select 1 from cicd_environments e where e.project_id = r.project_id and lower(e.name) = lower(v.env_name))
on conflict do nothing;

-- 배포가 새로 들여온 커밋: 06 = 12, 07 = 1·2(첫 배포), 08 = 13, 09 = 12, 10 = 13, 11 = 5–8, 12 = 9–11
create temporary table v1013_brought (deployment_id uuid, commit_id uuid) on commit drop;
insert into v1013_brought values
  ('0e1f2a3b-0006-4e00-8000-000000006006', '0a1b2c3d-0012-4a00-8000-000000001012'),
  ('0e1f2a3b-0007-4e00-8000-000000006007', '0a1b2c3d-0001-4a00-8000-000000001001'),
  ('0e1f2a3b-0007-4e00-8000-000000006007', '0a1b2c3d-0002-4a00-8000-000000001002'),
  ('0e1f2a3b-0008-4e00-8000-000000006008', '0a1b2c3d-0013-4a00-8000-000000001013'),
  ('0e1f2a3b-0009-4e00-8000-000000006009', '0a1b2c3d-0012-4a00-8000-000000001012'),
  ('0e1f2a3b-0010-4e00-8000-000000006010', '0a1b2c3d-0013-4a00-8000-000000001013'),
  ('0e1f2a3b-0011-4e00-8000-000000006011', '0a1b2c3d-0005-4a00-8000-000000001005'),
  ('0e1f2a3b-0011-4e00-8000-000000006011', '0a1b2c3d-0006-4a00-8000-000000001006'),
  ('0e1f2a3b-0011-4e00-8000-000000006011', '0a1b2c3d-0007-4a00-8000-000000001007'),
  ('0e1f2a3b-0011-4e00-8000-000000006011', '0a1b2c3d-0008-4a00-8000-000000001008'),
  ('0e1f2a3b-0012-4e00-8000-000000006012', '0a1b2c3d-0009-4a00-8000-000000001009'),
  ('0e1f2a3b-0012-4e00-8000-000000006012', '0a1b2c3d-0010-4a00-8000-000000001010'),
  ('0e1f2a3b-0012-4e00-8000-000000006012', '0a1b2c3d-0011-4a00-8000-000000001011');

insert into cicd_deployment_commits (deployment_id, commit_id)
select b.deployment_id, b.commit_id
  from v1013_brought b
 where exists (select 1 from cicd_deployments d where d.id = b.deployment_id)
   and exists (select 1 from cicd_commits c where c.id = b.commit_id)
on conflict do nothing;

-- 지금 환경에 든 커밋(added_at은 V29의 트리거가 들여온 배포 시각으로 채움)
insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id)
select d.environment_id, d.repository_id, b.commit_id, d.id
  from v1013_brought b
  join cicd_deployments d on d.id = b.deployment_id and d.environment_id is not null
  join cicd_commits c on c.id = b.commit_id
on conflict do nothing;

-- 머리: 스테이징 = 배포 12(V1012가 넣은 머리를 옮김), 운영 = 배포 10
insert into cicd_environment_heads (repository_id, environment_id, sha, deployment_id, deployed_at)
select d.repository_id, d.environment_id, d.sha, d.id, d.deployed_at
  from cicd_deployments d
 where d.id in ('0e1f2a3b-0012-4e00-8000-000000006012', '0e1f2a3b-0010-4e00-8000-000000006010')
   and d.environment_id is not null
on conflict (repository_id, environment_id) do update
  set sha = excluded.sha, deployment_id = excluded.deployment_id, deployed_at = excluded.deployed_at;

-- 검증 5건(스테이징, 윤서아)
insert into cicd_verifications (id, work_package_id, environment_id, result, reason, verified_by, verified_at, basis_at)
select v.id::uuid, w.id, e.id, v.result, v.reason, 'fe75bf4d-827d-553e-9e4a-fdb703b4a272', v.verified_at::timestamptz, v.basis_at::timestamptz
  from (values
    ('0f2a3b4c-0001-4f00-8000-000000007001', 13, 'passed', '', '2026-10-13T09:00:00Z', '2026-10-13T04:00:00Z'),
    ('0f2a3b4c-0002-4f00-8000-000000007002', 3,  'passed', '', '2026-10-17T01:00:00Z', '2026-10-16T02:00:00Z'),
    ('0f2a3b4c-0003-4f00-8000-000000007003', 5,  'passed', '', '2026-10-17T01:10:00Z', '2026-10-16T02:00:00Z'),
    ('0f2a3b4c-0004-4f00-8000-000000007004', 4,  'failed', '결제 실패 때 오류 문구가 영어로 나옵니다.', '2026-10-17T01:20:00Z', '2026-10-16T02:00:00Z'),
    ('0f2a3b4c-0005-4f00-8000-000000007005', 1,  'passed', '', '2026-10-17T01:30:00Z', '2026-10-16T02:00:00Z')
  ) as v(id, wp_number, result, reason, verified_at, basis_at)
  join work_packages w on w.project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac' and w.number = v.wp_number
  join cicd_environments e on e.project_id = w.project_id and lower(e.name) = '스테이징'
 where exists (select 1 from users where id = 'fe75bf4d-827d-553e-9e4a-fdb703b4a272')
on conflict do nothing;

-- 검증마다 저널 하나(앱이 쓰는 모양: changes = {}, cause = {kind, environment, reason?}). version = 그 작업의 max(version) + 1
insert into journals (id, work_package_id, version, user_id, notes_html, notes_text, is_internal, changes, cause, created_at, updated_at)
select v.id::uuid, ver.work_package_id,
       coalesce((select max(j.version) from journals j where j.work_package_id = ver.work_package_id), 0) + 1,
       ver.verified_by, '', '', false, '{}'::jsonb,
       case when ver.result = 'failed'
            then jsonb_build_object('kind', 'verification_failed', 'environment', '스테이징', 'reason', ver.reason)
            else jsonb_build_object('kind', 'verification_passed', 'environment', '스테이징') end,
       ver.verified_at, ver.verified_at
  from (values
    ('0f4c5d6e-0001-4f00-8000-000000009001', '0f2a3b4c-0001-4f00-8000-000000007001'),
    ('0f4c5d6e-0002-4f00-8000-000000009002', '0f2a3b4c-0002-4f00-8000-000000007002'),
    ('0f4c5d6e-0003-4f00-8000-000000009003', '0f2a3b4c-0003-4f00-8000-000000007003'),
    ('0f4c5d6e-0004-4f00-8000-000000009004', '0f2a3b4c-0004-4f00-8000-000000007004'),
    ('0f4c5d6e-0005-4f00-8000-000000009005', '0f2a3b4c-0005-4f00-8000-000000007005')
  ) as v(id, verification_id)
  join cicd_verifications ver on ver.id = v.verification_id::uuid
on conflict do nothing;

-- 실패 알림(검증 4): 작업 #4의 담당자 박준호와 공동 담당자 이하늘(윤서아 본인 제외, 활성 사용자). 개발용이라 메일은 보내지 않은 것으로 표시
insert into notifications (recipient_id, work_package_id, project_id, journal_id, actor_id, reason, created_at, updated_at, emailed_at)
select a.user_id, w.id, w.project_id, j.id, ver.verified_by, 'verification_failed', ver.verified_at, ver.verified_at, ver.verified_at
  from cicd_verifications ver
  join work_packages w on w.id = ver.work_package_id
  join journals j on j.id = '0f4c5d6e-0004-4f00-8000-000000009004'
  join work_package_assignees a on a.work_package_id = w.id
  join users u on u.id = a.user_id and u.status = 'active'
 where ver.id = '0f2a3b4c-0004-4f00-8000-000000007004'
   and a.user_id <> ver.verified_by
   and not exists (select 1 from notifications n where n.journal_id = j.id and n.recipient_id = a.user_id);

-- 릴리스 기록 2건: 배포 09(운영, 원본 스테이징) 항목 #13(검증 1), 배포 10 항목 #8(검증 없음 = unverified)
insert into cicd_releases (id, project_id, environment_id, source_environment_id, deployment_id, repository_id, deployed_at, created_at, partial, content_failed)
select v.id::uuid, r.project_id, d.environment_id,
       (select e.id from cicd_environments e where e.project_id = r.project_id and lower(e.name) = '스테이징'),
       d.id, r.id, d.deployed_at, d.deployed_at, false, false
  from (values
    ('0f3b4c5d-0001-4f00-8000-000000008001', '0e1f2a3b-0009-4e00-8000-000000006009'),
    ('0f3b4c5d-0002-4f00-8000-000000008002', '0e1f2a3b-0010-4e00-8000-000000006010')
  ) as v(id, deployment_id)
  join cicd_deployments d on d.id = v.deployment_id::uuid and d.environment_id is not null
  join cicd_repositories r on r.id = d.repository_id
on conflict do nothing;

insert into cicd_release_items (release_id, work_package_id, number, subject, verification_id, warning)
select v.release_id::uuid, w.id, w.number, w.subject,
       (select x.id from cicd_verifications x where x.id = v.verification_id::uuid), v.warning
  from (values
    ('0f3b4c5d-0001-4f00-8000-000000008001', 13, '0f2a3b4c-0001-4f00-8000-000000007001', null),
    ('0f3b4c5d-0002-4f00-8000-000000008002', 8,  null,                                   'unverified')
  ) as v(release_id, wp_number, verification_id, warning)
  join work_packages w on w.project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac' and w.number = v.wp_number
 where exists (select 1 from cicd_releases r where r.id = v.release_id::uuid)
on conflict do nothing;
