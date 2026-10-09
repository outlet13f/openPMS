-- openPMS 기본 데이터 (Flyway V3__seed.sql로 사용. V2는 라이브러리 테이블)
-- 색은 replica/design/tokens.json의 color.data 팔레트(흰 배경 대비 4.5:1 이상)에서 고른다.
-- 원본의 데모 데이터나 문구가 아니라 openPMS 고유 값이다. 관리자 계정은 여기서 만들지 않는다:
-- 첫 실행 때 OPENPMS_ADMIN_EMAIL로 지정한 계정에 일회용 설정 링크를 로그로 출력한다(architecture.md).

-- 역할·유형·상태·우선순위는 고정 id(fixed ids)를 쓴다: uuid5(backend/scripts/ids.py의 NAMESPACE, 'r-manager' 같은 이름).
-- 개발 데이터와 브라우저 점검이 같은 id를 쓰게 하려는 것이다.

-- 권한 키 (architecture.md "권한 키"와 같아야 한다)
-- project.view project.edit project.archive project.manage_modules project.manage_members
-- project.view_members project.manage_versions project.manage_categories project.manage_types
-- project.manage_custom_fields
-- wp.view wp.create wp.edit wp.delete wp.move wp.attach wp.comment wp.edit_own_comments wp.edit_comments
-- wp.view_internal_comments wp.add_internal_comments wp.manage_relations wp.manage_subtasks
-- wp.assign_versions wp.view_watchers wp.add_watchers wp.delete_watchers wp.export
-- query.save query.manage_public board.view board.manage
-- global.create_projects global.manage_users

insert into roles (id, name, scope, builtin, position, permissions) values
  ('0329397e-d933-5ac1-b27c-95889ba1a893', '프로젝트 관리자', 'project', null, 1, array[
    'project.view', 'project.edit', 'project.archive', 'project.manage_modules', 'project.manage_members',
    'project.view_members', 'project.manage_versions', 'project.manage_categories', 'project.manage_types',
    'project.manage_custom_fields',
    'wp.view', 'wp.create', 'wp.edit', 'wp.delete', 'wp.move', 'wp.attach', 'wp.comment',
    'wp.edit_own_comments', 'wp.edit_comments', 'wp.view_internal_comments', 'wp.add_internal_comments',
    'wp.manage_relations', 'wp.manage_subtasks', 'wp.assign_versions', 'wp.view_watchers',
    'wp.add_watchers', 'wp.delete_watchers', 'wp.export',
    'query.save', 'query.manage_public', 'board.view', 'board.manage']),
  ('6d4333fb-e975-5213-8ebc-552cca26e1d4', '멤버', 'project', null, 2, array[
    'project.view', 'project.view_members',
    'wp.view', 'wp.create', 'wp.edit', 'wp.move', 'wp.attach', 'wp.comment', 'wp.edit_own_comments',
    'wp.manage_relations', 'wp.manage_subtasks', 'wp.assign_versions', 'wp.view_watchers',
    'wp.add_watchers', 'wp.export',
    'query.save', 'board.view', 'board.manage']),
  ('f957f36a-72ca-5379-b8b9-c0e7dabe2d1e', '참관자', 'project', null, 3, array[
    'project.view', 'project.view_members',
    'wp.view', 'wp.comment', 'wp.edit_own_comments', 'wp.view_watchers', 'wp.export',
    'query.save', 'board.view']),
  ('21f83874-5c75-520e-aa32-76a829a19e30', '비멤버', 'project', 'non_member', 4, array[
    'project.view', 'wp.view', 'board.view']),
  ('43d2721e-1710-52d2-8ac7-255b586e6e18', '프로젝트 생성자', 'global', null, 5, array[
    'global.create_projects']);

insert into types (id, name, color, position, is_milestone, is_default, is_in_roadmap, attribute_groups) values
  ('dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc', '작업',     '#1f5fc4', 1, false, true,  true,
   '[{"name":"사람","attributes":["assignee","responsible"]},{"name":"일정","attributes":["dates","version"]},{"name":"진행률","attributes":["work","remaining_work","done_ratio"]},{"name":"세부 정보","attributes":["priority","category"]}]'),
  ('c1230443-eb9f-5805-801d-9f64fa8c86f7', '마일스톤', '#277a32', 2, true,  true,  true,
   '[{"name":"사람","attributes":["assignee","responsible"]},{"name":"일정","attributes":["dates","version"]}]'),
  ('97824664-f292-5fdd-bc57-5abcde69c015', '요약',     '#b24d0c', 3, false, true,  true,
   '[{"name":"사람","attributes":["assignee","responsible"]},{"name":"일정","attributes":["dates","version"]},{"name":"진행률","attributes":["work","remaining_work","done_ratio"]}]'),
  ('c0c4d51e-01ef-5481-8920-efeb97c72080', '기능',     '#7041c4', 4, false, false, true,
   '[{"name":"사람","attributes":["assignee","responsible"]},{"name":"일정","attributes":["dates","version"]},{"name":"세부 정보","attributes":["priority","category"]}]'),
  ('35b87fc8-00ab-537a-af55-db53bcfa5f78', '버그',     '#c02c39', 5, false, false, false,
   '[{"name":"사람","attributes":["assignee","responsible"]},{"name":"세부 정보","attributes":["priority","category","version"]}]');

insert into statuses (id, name, color, position, is_default, is_closed, is_readonly, default_done_ratio, excluded_from_totals) values
  ('fd59c461-989d-5bad-976a-f9b5c2ed169a', '신규',    '#0b7575', 1, true,  false, false, 0,   false),
  ('d47d49b0-2977-5885-a713-2547a7e4f91f', '진행 중', '#7041c4', 2, false, false, false, 50,  false),
  ('898be833-8e83-5e85-a8f5-35b82c2dbf2b', '검토 중', '#975800', 3, false, false, false, 80,  false),
  ('2cb0d2b8-1c77-588a-8207-a4c23535bb81', '보류',    '#5f6873', 4, false, false, false, 0,   false),
  ('5aaf4021-de87-5376-81f4-50668e28b555', '완료',    '#277a32', 5, false, true,  false, 100, false),
  ('8ab8a2d1-0d29-5b49-a263-6284c74a7680', '취소',    '#c02c39', 6, false, true,  true,  0,   true);

insert into priorities (id, name, color, position, is_default) values
  ('88fd7a3f-c7d8-5303-af34-54635d6c4430', '낮음', '#5f6873', 1, false),
  ('e2513703-c41d-5575-aca0-fd084ec26e06', '보통', '#1f5fc4', 2, true),
  ('c3527540-9f2f-5f6e-af9a-85ecee0dbd0c', '높음', '#975800', 3, false),
  ('ad169c7c-6852-59e6-b997-4cb8575645bb', '긴급', '#c02c39', 4, false);

-- 관리자·멤버는 모든 유형에서 모든 상태 간 전환 가능. 참관자는 상태를 바꾸지 못한다.
insert into workflows (type_id, role_id, from_status_id, to_status_id, applies_to)
select t.id, r.id, s_from.id, s_to.id, 'always'
from types t
cross join roles r
cross join statuses s_from
cross join statuses s_to
where r.name in ('프로젝트 관리자', '멤버')
  and s_from.id <> s_to.id;

insert into settings (key, value) values
  ('calendar.working_days',        '[1, 2, 3, 4, 5]'),          -- ISO 요일(월=1 … 일=7)
  ('calendar.week_starts_on',      '7'),                        -- 달력 첫 요일: 일요일
  ('calendar.hours_per_day',       '8'),
  ('calendar.duration_format',     '"days_and_hours"'),         -- days_and_hours | hours_only
  ('progress.mode',                '"work"'),                   -- work | status
  ('progress.totals',              '"weighted"'),               -- weighted | simple_average
  ('progress.closed_sets_100',     'false'),
  ('attachments.max_bytes',        '26214400'),                 -- 25MB
  ('journals.aggregation_minutes', '5'),
  ('notifications.poll_seconds',   '60'),
  ('i18n.default_locale',          '"ko"'),
  ('i18n.default_time_zone',       '"Asia/Seoul"'),
  ('auth.password_min_length',     '10'),
  ('auth.lockout_attempts',        '5'),
  ('auth.lockout_minutes',         '15'),
  ('auth.invitation_ttl_hours',    '168'),
  ('auth.password_reset_ttl_minutes', '60'),
  ('mail.from',                    '"openPMS <no-reply@example.com>"');
