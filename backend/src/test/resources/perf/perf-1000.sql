-- 성능 예산(replica/fixes.md 4번)의 측정 데이터: 작업 1,000건짜리 프로젝트 하나.
-- 요약 작업 50개 아래에 작업 19개씩(950개), 선후행 250개·관련 99개, 작업마다 이력 1~4건, 상세 측정용 작업 하나에 이력 40건,
-- 상태 보드 하나(카드 1,000장). 유형·상태·우선순위·역할은 스키마 시드(V3), 사람 6명은 개발 데이터와 같은 id(없으면 비밀번호 없이
-- 만듦)라 개발 DB에도 새로 설치한 시험용 DB에도 들어갑니다. 몇 번 실행해도 같은 모양이 됩니다.
--   JUnit: PerfBudgetIT가 읽어 실행   e2e: perf.spec.ts가 dev.sh perf-seed로 실행   부하 측정(scripts/perf_load.py) 전: 시험용 설치의 db에 psql로
delete from projects where identifier in ('perf-1000', 'perf-sd');

-- The six people the items are assigned to. The dev data has them already; on a fresh installation (a test one) they are
-- made here without a password, so nobody can sign in as them — measure with your own administrator account, which sees
-- every project. Types, statuses, priorities and roles come from the schema's own seed (V3), present everywhere.
insert into users (id, email, name, status, is_admin) values
  ('1976e561-6423-589e-aa23-3ceab10de751', 'seoyeon.kim@openpms.local', '김서연', 'active', false),
  ('c48ce4ad-3a64-55ab-a95c-2902395dbb46', 'junho.park@openpms.local', '박준호', 'active', false),
  ('f0712dee-a7e4-5b9a-a9c5-7d3682ce763a', 'haneul.lee@openpms.local', '이하늘', 'active', false),
  ('1fe0bccd-f730-556b-bc23-09b76271d5ab', 'minji.choi@openpms.local', '최민지', 'active', false),
  ('50aa22c1-a4af-5b91-905b-7c48aa4619c2', 'doyoon.han@openpms.local', '한도윤', 'active', false),
  ('9c86b115-f372-542f-9d5d-25b2297d9558', 'alex.kim@openpms.local', 'Alex Kim', 'active', false)
on conflict do nothing;
insert into user_preferences (user_id)
select id from users where email in ('seoyeon.kim@openpms.local', 'junho.park@openpms.local', 'haneul.lee@openpms.local',
                                     'minji.choi@openpms.local', 'doyoon.han@openpms.local', 'alex.kim@openpms.local')
on conflict do nothing;

insert into projects (identifier, name, description_html, description_text, is_public, enabled_modules, created_by)
values ('perf-1000', '성능 측정 (작업 1,000건)', '<p>응답 시간 예산을 재는 데 쓰는 프로젝트입니다.</p>', '응답 시간 예산을 재는 데 쓰는 프로젝트입니다.', false,
        array['work_packages', 'gantt', 'boards', 'calendar', 'team_planner', 'time_tracking']::text[], '1976e561-6423-589e-aa23-3ceab10de751');

insert into project_types (project_id, type_id)
select p.id, t.id from projects p cross join types t where p.identifier = 'perf-1000';

-- 김서연(관리자 역할), 나머지 다섯 명은 멤버
with m as (
  insert into members (project_id, user_id)
  select p.id, u.id from projects p cross join users u
  where p.identifier = 'perf-1000' and u.email in ('seoyeon.kim@openpms.local', 'junho.park@openpms.local', 'haneul.lee@openpms.local',
                                                   'minji.choi@openpms.local', 'doyoon.han@openpms.local', 'alex.kim@openpms.local')
  returning id, user_id
)
insert into member_roles (member_id, role_id)
select m.id, case when m.user_id = '1976e561-6423-589e-aa23-3ceab10de751' then '0329397e-d933-5ac1-b27c-95889ba1a893'::uuid
                  else '6d4333fb-e975-5213-8ebc-552cca26e1d4'::uuid end
from m;

insert into versions (project_id, name, description, status, start_date, end_date)
select p.id, v.name, '', v.status, v.s::date, v.e::date
from projects p cross join (values ('1차 릴리스', 'closed', '2026-07-01', '2026-08-31'), ('2차 릴리스', 'open', '2026-09-01', '2026-10-31'),
                                   ('3차 릴리스', 'open', '2026-11-01', '2026-12-31'), ('백로그', 'open', null, null)) as v(name, status, s, e)
where p.identifier = 'perf-1000';

insert into categories (project_id, name)
select p.id, c from projects p cross join unnest(array['프론트엔드', '백엔드', '인프라', '디자인']) as c where p.identifier = 'perf-1000';

-- n 1..50: 요약 작업(에픽), n 51..1000: 그 아래 작업. 번호는 시퀀스가 매기고, n과 id는 아래 단계에서 쓰려고 임시 표에 남깁니다
drop table if exists perf_wp;
create temp table perf_wp (n int primary key, id uuid not null default gen_random_uuid(), epic int not null, i int not null,
                           start_date date, due_date date);
insert into perf_wp (n, epic, i)
select n, case when n <= 50 then n else (n - 51) / 19 + 1 end, case when n <= 50 then -1 else (n - 51) % 19 end
from generate_series(1, 1000) as n;
-- 에픽마다 3일씩 늦게 시작, 그 안의 작업은 4일 간격으로 2~4일짜리라 앞 작업이 끝난 뒤에 다음 작업이 시작합니다
update perf_wp set start_date = date '2026-07-01' + (epic - 1) * 3 + i * 4 where i >= 0;
update perf_wp set due_date = start_date + 1 + n % 3 where i >= 0;
update perf_wp e set start_date = c.s, due_date = c.d
from (select epic, min(start_date) s, max(due_date) d from perf_wp where i >= 0 group by epic) c where e.i = -1 and e.epic = c.epic;

insert into work_packages (id, project_id, type_id, status_id, priority_id, subject, description_html, description_text, author_id,
                           assignee_id, responsible_id, version_id, category_id, parent_id, start_date, due_date, duration,
                           schedule_manually, work_hours, remaining_hours, done_ratio, created_at, updated_at)
select w.id, p.id,
       -- 유형: 에픽은 요약, 그 아래는 작업이 대부분이고 기능·버그·마일스톤이 섞입니다
       case when w.i = -1 then '97824664-f292-5fdd-bc57-5abcde69c015'::uuid
            when w.i = 18 then 'c1230443-eb9f-5805-801d-9f64fa8c86f7'::uuid
            when w.i % 10 = 9 then '35b87fc8-00ab-537a-af55-db53bcfa5f78'::uuid
            when w.i % 7 = 3 then 'c0c4d51e-01ef-5481-8920-efeb97c72080'::uuid
            else 'dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc'::uuid end,
       -- 상태: 앞쪽 에픽은 거의 완료, 뒤로 갈수록 신규가 많음
       (array['fd59c461-989d-5bad-976a-f9b5c2ed169a', 'd47d49b0-2977-5885-a713-2547a7e4f91f', '898be833-8e83-5e85-a8f5-35b82c2dbf2b',
              '2cb0d2b8-1c77-588a-8207-a4c23535bb81', '5aaf4021-de87-5376-81f4-50668e28b555', '8ab8a2d1-0d29-5b49-a263-6284c74a7680']::uuid[])
         [case when w.n % 97 = 0 then 6
               when w.epic <= 12 then case when w.n % 9 = 0 then 3 else 5 end
               when w.epic <= 30 then 1 + w.n % 4
               else case when w.n % 5 = 0 then 2 else 1 end end],
       (array['88fd7a3f-c7d8-5303-af34-54635d6c4430', 'e2513703-c41d-5575-aca0-fd084ec26e06', 'e2513703-c41d-5575-aca0-fd084ec26e06',
              'c3527540-9f2f-5f6e-af9a-85ecee0dbd0c', 'ad169c7c-6852-59e6-b997-4cb8575645bb']::uuid[])[1 + w.n % 5],
       case when w.i = -1 then '에픽 ' || w.epic || ': ' || (array['고객 포털', '결제', '검색', '알림', '관리 화면', '보고서', '모바일', '권한', '데이터 이관', '접근성'])[1 + w.epic % 10]
            when w.n = 60 then '상세 화면 측정용 작업'
            else (array['로그인', '결제', '검색', '알림', '설정', '보고서', '대시보드', '권한', '업로드', '내보내기', '일정', '댓글'])[1 + w.n % 12] || ' ' ||
                 (array['화면 개선', 'API 연동', '오류 수정', '테스트 작성', '문서 정리', '성능 점검', '디자인 검토'])[1 + w.n % 7] || ' (' || w.n || ')' end,
       case when w.n % 3 = 0 then '<p>' || w.n || '번 작업의 설명입니다. 완료 조건과 확인 방법을 적습니다.</p><ul><li>화면에서 확인</li><li>테스트 통과</li></ul>' else '' end,
       case when w.n % 3 = 0 then w.n || '번 작업의 설명입니다. 완료 조건과 확인 방법을 적습니다. 화면에서 확인 테스트 통과' else '' end,
       '1976e561-6423-589e-aa23-3ceab10de751',
       case when w.n % 13 = 0 then null
            else (array['1976e561-6423-589e-aa23-3ceab10de751', 'c48ce4ad-3a64-55ab-a95c-2902395dbb46', 'f0712dee-a7e4-5b9a-a9c5-7d3682ce763a',
                        '1fe0bccd-f730-556b-bc23-09b76271d5ab', '50aa22c1-a4af-5b91-905b-7c48aa4619c2', '9c86b115-f372-542f-9d5d-25b2297d9558']::uuid[])[1 + w.n % 6] end,
       case when w.i = -1 then '1976e561-6423-589e-aa23-3ceab10de751'::uuid end,
       (select v.id from versions v where v.project_id = p.id and v.name = (array['1차 릴리스', '2차 릴리스', '3차 릴리스', '백로그'])[1 + least(3, (w.epic - 1) / 13)]),
       case when w.n % 4 = 0 then null else (select c.id from categories c where c.project_id = p.id and c.name = (array['프론트엔드', '백엔드', '인프라', '디자인'])[1 + w.n % 4]) end,
       null,
       w.start_date, case when w.i = 18 then w.start_date else w.due_date end,
       greatest(1, (select count(*) from generate_series(w.start_date, case when w.i = 18 then w.start_date else w.due_date end, interval '1 day') d
                    where extract(isodow from d) < 6))::int,
       w.i <> -1,
       case when w.i in (-1, 18) then null else 4 + (w.n % 9) * 4 end,
       case when w.i in (-1, 18) then null when w.epic <= 12 then 0 else 4 + (w.n % 9) * 2 end,
       case when w.i in (-1, 18) then null when w.epic <= 12 then 100 else 50 - (w.n % 9) * 5 end,
       timestamptz '2026-06-20 01:00:00+00' + w.n * interval '17 minutes',
       timestamptz '2026-06-20 01:00:00+00' + w.n * interval '17 minutes'
from perf_wp w cross join projects p
where p.identifier = 'perf-1000'
order by w.n;

-- 담당자 목록(공동 담당자, V18): 주 담당자가 첫 사람, 다섯 개 중 하나는 한 사람 더(예상 시간을 나눠 가짐)
insert into work_package_assignees (work_package_id, user_id, position, work_hours)
select w.id, w.assignee_id, 0, case when pw.n % 5 = 0 and w.work_hours is not null then round(w.work_hours / 2, 2) end
from work_packages w join perf_wp pw on pw.id = w.id where w.assignee_id is not null;
insert into work_package_assignees (work_package_id, user_id, position, work_hours)
select w.id, u.id, 1, case when w.work_hours is not null then round(w.work_hours / 2, 2) end
from work_packages w join perf_wp pw on pw.id = w.id
join users u on u.id = (array['c48ce4ad-3a64-55ab-a95c-2902395dbb46', 'f0712dee-a7e4-5b9a-a9c5-7d3682ce763a', '1fe0bccd-f730-556b-bc23-09b76271d5ab',
                               '50aa22c1-a4af-5b91-905b-7c48aa4619c2', '9c86b115-f372-542f-9d5d-25b2297d9558', '1976e561-6423-589e-aa23-3ceab10de751']::uuid[])[1 + (pw.n / 5) % 6]
where w.assignee_id is not null and pw.n % 5 = 0 and u.id <> w.assignee_id;

update work_packages c set parent_id = e.id
from perf_wp cw join perf_wp e on e.i = -1 and e.epic = cw.epic
where cw.i >= 0 and c.id = cw.id;

-- 에픽의 합계(자식에서 계산되는 값)
update work_packages e set total_work_hours = t.work, total_remaining_hours = t.remaining,
       total_done_ratio = case when t.work > 0 then round(100 * (t.work - t.remaining) / t.work) end
from (select parent_id, sum(work_hours) work, sum(remaining_hours) remaining from work_packages where parent_id is not null group by parent_id) t
where e.id = t.parent_id and e.id in (select id from perf_wp where i = -1);
update work_packages w set total_work_hours = w.work_hours, total_remaining_hours = w.remaining_hours, total_done_ratio = w.done_ratio
where w.id in (select id from perf_wp where i >= 0);

-- 선후행: 에픽마다 앞의 작업 다섯 개가 사슬(250개). 관련: 이웃 에픽의 11번째 작업끼리(49개)와 에픽 안의 13번째·15번째(50개)
insert into relations (from_id, to_id, type, created_by)
select a.id, b.id, 'precedes', '1976e561-6423-589e-aa23-3ceab10de751' from perf_wp a join perf_wp b on b.epic = a.epic and b.i = a.i + 1 where a.i between 0 and 4;
insert into relations (from_id, to_id, type, created_by)
select a.id, b.id, 'relates', '1976e561-6423-589e-aa23-3ceab10de751' from perf_wp a join perf_wp b on b.epic = a.epic + 1 and b.i = 10 where a.i = 10;
insert into relations (from_id, to_id, type, created_by)
select a.id, b.id, 'relates', '1976e561-6423-589e-aa23-3ceab10de751' from perf_wp a join perf_wp b on b.epic = a.epic and b.i = 14 where a.i = 12;

-- 이력: 모두 만든 기록 1건, 열 개 중 셋은 상태 변경·코멘트가 더 있음, 상세 측정용 작업은 40건
insert into journals (work_package_id, version, user_id, changes, created_at, updated_at)
select w.id, 1, '1976e561-6423-589e-aa23-3ceab10de751', '{"_created": [null, true]}'::jsonb, wp.created_at, wp.created_at
from perf_wp w join work_packages wp on wp.id = w.id;
insert into journals (work_package_id, version, user_id, changes, created_at, updated_at)
select w.id, 2, 'c48ce4ad-3a64-55ab-a95c-2902395dbb46',
       '{"statusId": ["fd59c461-989d-5bad-976a-f9b5c2ed169a", "d47d49b0-2977-5885-a713-2547a7e4f91f"]}'::jsonb,
       wp.created_at + interval '1 day', wp.created_at + interval '1 day'
from perf_wp w join work_packages wp on wp.id = w.id where w.n % 10 in (1, 4, 7) and w.n <> 60;
insert into journals (work_package_id, version, user_id, notes_html, notes_text, changes, created_at, updated_at)
select w.id, v.v, (array['c48ce4ad-3a64-55ab-a95c-2902395dbb46', 'f0712dee-a7e4-5b9a-a9c5-7d3682ce763a'])[1 + v.v % 2]::uuid,
       '<p>' || v.v || '번째 의견입니다. 진행 상황을 공유합니다.</p>', v.v || '번째 의견입니다. 진행 상황을 공유합니다.', '{}'::jsonb,
       wp.created_at + v.v * interval '1 day', wp.created_at + v.v * interval '1 day'
from perf_wp w join work_packages wp on wp.id = w.id cross join generate_series(3, 4) as v(v) where w.n % 10 in (1, 4, 7) and w.n <> 60;
insert into journals (work_package_id, version, user_id, notes_html, notes_text, changes, created_at, updated_at)
select w.id, v.v, (array['1976e561-6423-589e-aa23-3ceab10de751', 'c48ce4ad-3a64-55ab-a95c-2902395dbb46', 'f0712dee-a7e4-5b9a-a9c5-7d3682ce763a'])[1 + v.v % 3]::uuid,
       case when v.v % 2 = 0 then '<p>' || v.v || '번째 코멘트: 확인했습니다. <strong>다음 단계</strong>로 넘깁니다.</p>' else '' end,
       case when v.v % 2 = 0 then v.v || '번째 코멘트: 확인했습니다. 다음 단계로 넘깁니다.' else '' end,
       case when v.v % 2 = 1 then jsonb_build_object('subject', jsonb_build_array('이전 제목 ' || (v.v - 1), '상세 화면 측정용 작업')) else '{}'::jsonb end,
       wp.created_at + v.v * interval '3 hours', wp.created_at + v.v * interval '3 hours'
from perf_wp w join work_packages wp on wp.id = w.id cross join generate_series(2, 40) as v(v) where w.n = 60;

insert into watchers (work_package_id, user_id)
select w.id, '1976e561-6423-589e-aa23-3ceab10de751' from perf_wp w where w.n % 20 = 0;

-- 상태 보드: 상태마다 열 하나, 카드 1,000장
with b as (
  insert into boards (project_id, name, kind, created_by)
  select p.id, '상태 보드', 'status', '1976e561-6423-589e-aa23-3ceab10de751' from projects p where p.identifier = 'perf-1000'
  returning id
)
insert into board_columns (board_id, position, status_id)
select b.id, s.position, s.id from b cross join statuses s;

drop table perf_wp;

-- 서비스 데스크(ITSM 1단계) 예산용 두 번째 프로젝트 perf-sd: 장애 티켓 200건(요청자는 최민지 한 사람, 정책 하나).
-- 작업 1,000건짜리 perf-1000과 섞지 않는다(그 프로젝트의 건수·목록·보드 예산이 그대로여야 해서). 최민지는 멤버가 아니라 포털로만 본다.
-- 개발 DB에서는 최민지가 비밀번호를 가지고 있어(PerfBudgetIT가 로그인) 포털 목록을 잴 수 있다.
insert into projects (identifier, name, description_html, description_text, is_public, enabled_modules, created_by)
values ('perf-sd', '성능 측정 (서비스 데스크 티켓 200건)', '<p>서비스 데스크 목록과 포털 응답 시간 예산을 재는 데 쓰는 프로젝트입니다.</p>', '서비스 데스크 목록과 포털 응답 시간 예산을 재는 데 쓰는 프로젝트입니다.', false,
        array['work_packages', 'boards', 'service_desk']::text[], '1976e561-6423-589e-aa23-3ceab10de751');
insert into project_types (project_id, type_id)
select p.id, t.id from projects p cross join types t where p.identifier = 'perf-sd' and t.id in ('2dcd4691-d4a9-5bf0-8908-e4740b42616e', 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb');
with m as (
  insert into members (project_id, user_id)
  select p.id, '1976e561-6423-589e-aa23-3ceab10de751' from projects p where p.identifier = 'perf-sd'
  returning id
)
insert into member_roles (member_id, role_id) select id, '0329397e-d933-5ac1-b27c-95889ba1a893' from m;
insert into sla_policies (project_id, type_id, priority_id, calendar_id, first_response_minutes, resolve_minutes)
select p.id, '2dcd4691-d4a9-5bf0-8908-e4740b42616e', 'e2513703-c41d-5575-aca0-fd084ec26e06', '27ee2635-3f4b-5407-8498-78ce0dbf371c', 60, 480
from projects p where p.identifier = 'perf-sd';

drop table if exists perf_sd;
create temp table perf_sd (n int primary key, id uuid not null default gen_random_uuid());
insert into perf_sd (n) select n from generate_series(1, 200) n;
insert into work_packages (id, project_id, type_id, status_id, priority_id, subject, description_html, description_text, author_id, assignee_id, created_at, updated_at)
select s.id, p.id, '2dcd4691-d4a9-5bf0-8908-e4740b42616e',
       (array['198d200e-a1ba-5692-ae3c-6ba0bc4319ad', 'd34fe673-69b6-56f9-85fd-2cb8198cfba5', 'b42e3256-ffde-5d57-a323-43c8be0d70bd',
              '8201e445-a46a-5135-a972-3f83ee10c3ba']::uuid[])[1 + s.n % 4],
       'e2513703-c41d-5575-aca0-fd084ec26e06',
       (array['VPN', '메일', '프린터', '위키', '회계 서버', '와이파이'])[1 + s.n % 6] || ' 장애 신고 (' || s.n || ')',
       '<p>' || s.n || '번 장애 신고입니다. 증상과 발생 시각을 적습니다.</p>', s.n || '번 장애 신고입니다. 증상과 발생 시각을 적습니다.',
       '1fe0bccd-f730-556b-bc23-09b76271d5ab',
       case when s.n % 4 = 0 then null else (array['c48ce4ad-3a64-55ab-a95c-2902395dbb46', 'f0712dee-a7e4-5b9a-a9c5-7d3682ce763a'])[1 + s.n % 2]::uuid end,
       now() - s.n * interval '30 minutes', now() - s.n * interval '30 minutes'
from perf_sd s cross join projects p where p.identifier = 'perf-sd';
insert into service_tickets (work_package_id, requester_id, channel, sla_policy_id, first_response_due_at, first_responded_at, resolve_due_at, resolved_at,
                             requester_mailed_at, requester_mailed_status_id, created_at, updated_at)
select w.id, '1fe0bccd-f730-556b-bc23-09b76271d5ab', 'portal', (select sp.id from sla_policies sp where sp.project_id = p.id),
       w.created_at + interval '1 hour', case when w.status_id <> '198d200e-a1ba-5692-ae3c-6ba0bc4319ad' then w.created_at + interval '20 minutes' end,
       w.created_at + interval '8 hours', case when w.status_id = '8201e445-a46a-5135-a972-3f83ee10c3ba' then w.created_at + interval '3 hours' end,
       now(), w.status_id, w.created_at, w.created_at
from work_packages w join projects p on p.id = w.project_id where p.identifier = 'perf-sd';
insert into work_package_assignees (work_package_id, user_id, position)
select id, assignee_id, 0 from work_packages w where project_id = (select id from projects where identifier = 'perf-sd') and assignee_id is not null
on conflict (work_package_id, user_id) do nothing;
-- 이력: 모두 만든 기록 1건, 측정용 티켓(첫 번째)에는 공개 답변 8건과 내부 코멘트 2건
insert into journals (work_package_id, version, user_id, changes, created_at, updated_at)
select w.id, 1, w.author_id, '{"_created": [null, true]}'::jsonb, w.created_at, w.created_at
from work_packages w join projects p on p.id = w.project_id where p.identifier = 'perf-sd';
insert into journals (work_package_id, version, user_id, notes_html, notes_text, is_internal, changes, created_at, updated_at)
select w.id, v.v, case when v.v % 2 = 0 then 'c48ce4ad-3a64-55ab-a95c-2902395dbb46'::uuid else w.author_id end,
       '<p>' || v.v || '번째 답변입니다.</p>', v.v || '번째 답변입니다.', v.v in (4, 7), '{}'::jsonb,
       w.created_at + v.v * interval '10 minutes', w.created_at + v.v * interval '10 minutes'
from work_packages w join projects p on p.id = w.project_id and p.identifier = 'perf-sd' join perf_sd s on s.id = w.id
cross join generate_series(2, 11) v(v) where s.n = 1;
drop table perf_sd;
