-- 개발 데이터: CI/CD 연동의 "개발" 탭을 보여 줄 연동·저장소·커밋·MR·연결 (V26 뒤).
-- 연동은 비밀 없이 active = false (SQL로는 AES-GCM 값을 만들 수 없음). 작업은 번호로 찾는다(web-platform: 4 결제 API 연동, 8 로그인 화면 글자 대비 부족, 11 검색 결과 정렬, 13 배포 자동화 스크립트 정리).

update projects set enabled_modules = enabled_modules || array['cicd']
  where id in ('935daeb0-d1aa-57f4-88e1-0050b30413ac', '09e67323-1a1d-5ccd-854b-78e0628e38c0')
    and not ('cicd' = any (enabled_modules));

-- 박준호: web-platform에서 개발 리더 역할을 하나 더
insert into member_roles (member_id, role_id) values ('2b8a9eec-7c9f-550c-8135-5482635b868d', '8e9c194d-fcdf-5c47-b081-ab2b2b61c89a');

insert into cicd_connections (id, kind, name, base_url, hook_key, active, created_by, created_at, updated_at) values
  ('fab25892-ec02-5f18-85b0-eebc81857f6b', 'gitlab', '사내 GitLab(개발용)', 'https://gitlab.example.invalid', 'dev-gitlab-hook-key', false,
   '1976e561-6423-589e-aa23-3ceab10de751', '2026-10-01T00:30:00Z', '2026-10-01T00:30:00Z');

insert into cicd_repositories (id, connection_id, external_id, full_path, web_url, project_id, created_at) values
  ('fcf66281-3d0a-5a43-bb99-56e63d74c3ea', 'fab25892-ec02-5f18-85b0-eebc81857f6b', '101', 'web/portal',
   'https://gitlab.example.invalid/web/portal', '935daeb0-d1aa-57f4-88e1-0050b30413ac', '2026-10-01T00:35:00Z'),
  ('5771ab90-c8dc-56aa-b1df-7806feeb0f08', 'fab25892-ec02-5f18-85b0-eebc81857f6b', '102', 'mobile/app',
   'https://gitlab.example.invalid/mobile/app', '09e67323-1a1d-5ccd-854b-78e0628e38c0', '2026-10-01T00:36:00Z');

insert into cicd_commits (id, repository_id, sha, title, author_name, author_email, user_id, committed_at, web_url) values
  ('0a1b2c3d-0001-4a00-8000-000000001001', 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea', '1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d',
   '결제 API 클라이언트 추가 (#4)', '박준호', 'junho.park@openpms.local', 'c48ce4ad-3a64-55ab-a95c-2902395dbb46', '2026-10-05T02:10:00Z',
   'https://gitlab.example.invalid/web/portal/-/commit/1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d'),
  ('0a1b2c3d-0002-4a00-8000-000000001002', 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea', '2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e',
   '결제 승인 응답 처리 (#4)', '박준호', 'junho.park@openpms.local', 'c48ce4ad-3a64-55ab-a95c-2902395dbb46', '2026-10-06T05:45:00Z',
   'https://gitlab.example.invalid/web/portal/-/commit/2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e'),
  ('0a1b2c3d-0003-4a00-8000-000000001003', 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea', '3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f',
   '로그인 화면 글자 색 대비 개선 (#8)', '이하늘', 'haneul.lee@openpms.local', 'f0712dee-a7e4-5b9a-a9c5-7d3682ce763a', '2026-10-07T01:20:00Z',
   'https://gitlab.example.invalid/web/portal/-/commit/3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f'),
  ('0a1b2c3d-0004-4a00-8000-000000001004', 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea', '4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f70',
   '검색 결과 날짜 정렬 수정 (#11)', 'Alex Kim', 'alex.kim@openpms.local', '9c86b115-f372-542f-9d5d-25b2297d9558', '2026-10-08T07:05:00Z',
   'https://gitlab.example.invalid/web/portal/-/commit/4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f70');

insert into cicd_merge_requests (id, repository_id, iid, title, state, source_branch, target_branch, author_name, user_id, merged_at, updated_at, web_url) values
  ('0b1c2d3e-0001-4b00-8000-000000002001', 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea', 12, '결제 API 연동 (#4)', 'merged', 'feature/4-payment-api', 'main',
   '박준호', 'c48ce4ad-3a64-55ab-a95c-2902395dbb46', '2026-10-07T03:00:00Z', '2026-10-07T03:00:00Z',
   'https://gitlab.example.invalid/web/portal/-/merge_requests/12'),
  ('0b1c2d3e-0002-4b00-8000-000000002002', 'fcf66281-3d0a-5a43-bb99-56e63d74c3ea', 13, '로그인 화면 대비 개선', 'open', 'fix/8-login-contrast', 'main',
   '이하늘', 'f0712dee-a7e4-5b9a-a9c5-7d3682ce763a', null, '2026-10-09T04:30:00Z',
   'https://gitlab.example.invalid/web/portal/-/merge_requests/13');

insert into work_package_cicd_links (id, work_package_id, commit_id, merge_request_id, source, created_by, created_at, dismissed_by, dismissed_at) values
  ('0c1d2e3f-0001-4c00-8000-000000003001', (select id from work_packages where number = 4 and project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac'), '0a1b2c3d-0001-4a00-8000-000000001001', null, 'message', null, '2026-10-05T02:11:00Z', null, null),
  ('0c1d2e3f-0002-4c00-8000-000000003002', (select id from work_packages where number = 4 and project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac'), '0a1b2c3d-0002-4a00-8000-000000001002', null, 'message', null, '2026-10-06T05:46:00Z', null, null),
  ('0c1d2e3f-0003-4c00-8000-000000003003', (select id from work_packages where number = 4 and project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac'), null, '0b1c2d3e-0001-4b00-8000-000000002001', 'branch', null, '2026-10-07T00:00:00Z', null, null),
  ('0c1d2e3f-0004-4c00-8000-000000003004', (select id from work_packages where number = 8 and project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac'), '0a1b2c3d-0003-4a00-8000-000000001003', null, 'message', null, '2026-10-07T01:21:00Z', null, null),
  ('0c1d2e3f-0005-4c00-8000-000000003005', (select id from work_packages where number = 8 and project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac'), null, '0b1c2d3e-0002-4b00-8000-000000002002', 'branch', null, '2026-10-09T04:31:00Z', null, null),
  ('0c1d2e3f-0006-4c00-8000-000000003006', (select id from work_packages where number = 11 and project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac'), '0a1b2c3d-0004-4a00-8000-000000001004', null, 'message', null, '2026-10-08T07:06:00Z', null, null),
  -- 끊은 연결: 13번 작업이 커밋 4를 잘못 잇던 것을 서연이 끊음
  ('0c1d2e3f-0007-4c00-8000-000000003007', (select id from work_packages where number = 13 and project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac'), '0a1b2c3d-0004-4a00-8000-000000001004', null, 'message', null, '2026-10-08T07:06:00Z',
   '1976e561-6423-589e-aa23-3ceab10de751', '2026-10-09T01:00:00Z');
