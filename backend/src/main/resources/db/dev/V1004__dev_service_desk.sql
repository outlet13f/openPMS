-- 개발 데이터: 서비스 데스크(V20 뒤). 처리자는 박준호·이하늘(1선 지원), 요청자는 멤버가 아닌 최민지·한도윤.
-- 정책: 장애 긴급 24시간(15분/2시간), 장애 높음·보통·낮음과 서비스 요청 보통은 평일 근무 시간.
insert into projects (id, identifier, name, description_html, description_text, is_public, enabled_modules)
values ('a02eb82b-c707-5a81-b0ce-71ea6d523f21', 'service-desk', 'IT 서비스 데스크',
        '<p>사내 IT 장애 신고와 서비스 요청을 받습니다.</p>', '사내 IT 장애 신고와 서비스 요청을 받습니다.', false,
        '{work_packages,boards,service_desk}');
insert into project_types (project_id, type_id) values
  ('a02eb82b-c707-5a81-b0ce-71ea6d523f21', '2dcd4691-d4a9-5bf0-8908-e4740b42616e'),
  ('a02eb82b-c707-5a81-b0ce-71ea6d523f21', 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb');

insert into user_groups (id, name) values
  ('25629a29-9f0b-50a1-a812-f04018678b83', '1선 지원'),
  ('501f6405-73e1-5d3b-9f81-b89483b26b36', '인프라');
insert into user_group_members (group_id, user_id)
select '25629a29-9f0b-50a1-a812-f04018678b83', id from users where email in ('junho.park@openpms.local', 'haneul.lee@openpms.local');
insert into user_group_members (group_id, user_id)
select '501f6405-73e1-5d3b-9f81-b89483b26b36', id from users where email = 'alex.kim@openpms.local';

with m as (
  insert into members (project_id, user_id)
  select 'a02eb82b-c707-5a81-b0ce-71ea6d523f21', id from users where email in ('junho.park@openpms.local', 'haneul.lee@openpms.local')
  returning id
)
insert into member_roles (member_id, role_id) select id, '6d4333fb-e975-5213-8ebc-552cca26e1d4' from m;

insert into sla_policies (project_id, type_id, priority_id, calendar_id, first_response_minutes, resolve_minutes) values
  ('a02eb82b-c707-5a81-b0ce-71ea6d523f21', '2dcd4691-d4a9-5bf0-8908-e4740b42616e', 'ad169c7c-6852-59e6-b997-4cb8575645bb', '867264b7-e4ac-5081-b799-9329cc748825', 15, 120),
  ('a02eb82b-c707-5a81-b0ce-71ea6d523f21', '2dcd4691-d4a9-5bf0-8908-e4740b42616e', 'c3527540-9f2f-5f6e-af9a-85ecee0dbd0c', '27ee2635-3f4b-5407-8498-78ce0dbf371c', 30, 240),
  ('a02eb82b-c707-5a81-b0ce-71ea6d523f21', '2dcd4691-d4a9-5bf0-8908-e4740b42616e', 'e2513703-c41d-5575-aca0-fd084ec26e06', '27ee2635-3f4b-5407-8498-78ce0dbf371c', 60, 480),
  ('a02eb82b-c707-5a81-b0ce-71ea6d523f21', '2dcd4691-d4a9-5bf0-8908-e4740b42616e', '88fd7a3f-c7d8-5303-af34-54635d6c4430', '27ee2635-3f4b-5407-8498-78ce0dbf371c', 240, 1440),
  ('a02eb82b-c707-5a81-b0ce-71ea6d523f21', 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb', 'e2513703-c41d-5575-aca0-fd084ec26e06', '27ee2635-3f4b-5407-8498-78ce0dbf371c', 240, 2400);

-- 행렬(영향도 × 긴급도): 1·1 긴급, 1·2와 2·1 높음, 3·3 낮음, 나머지 보통
insert into priority_matrix (project_id, impact, urgency, priority_id)
select 'a02eb82b-c707-5a81-b0ce-71ea6d523f21', i, u, case
  when i = 1 and u = 1 then 'ad169c7c-6852-59e6-b997-4cb8575645bb'::uuid
  when (i = 1 and u = 2) or (i = 2 and u = 1) then 'c3527540-9f2f-5f6e-af9a-85ecee0dbd0c'::uuid
  when i = 3 and u = 3 then '88fd7a3f-c7d8-5303-af34-54635d6c4430'::uuid
  else 'e2513703-c41d-5575-aca0-fd084ec26e06'::uuid end
from generate_series(1, 3) i cross join generate_series(1, 3) u;

-- 요청자 메일은 이미 나간 것으로 둔다(requester_mailed_at·requester_mailed_status_id) — 개발 서버의 portal-mail 작업이 시작하자마자 접수 확인·상태 알림을 보내지 않게.
-- 티켓 6건. 기한은 개발 화면에서 상태가 골고루 보이게 지금 기준으로 둔다(실제 계산은 앱이 다음 저장 때 다시 함).
with t (subject, type_id, status_id, priority_id, requester, assignee, grp, channel, created, resp_due, responded, res_due, paused, breached) as (values
  ('VPN 접속이 자꾸 끊깁니다', '2dcd4691-d4a9-5bf0-8908-e4740b42616e', '198d200e-a1ba-5692-ae3c-6ba0bc4319ad', 'c3527540-9f2f-5f6e-af9a-85ecee0dbd0c',
   'minji.choi@openpms.local', null, '25629a29-9f0b-50a1-a812-f04018678b83', 'portal', now() - interval '20 minutes', now() + interval '10 minutes', null, now() + interval '3 hours', null, false),
  ('회계 서버 응답 없음', '2dcd4691-d4a9-5bf0-8908-e4740b42616e', 'd34fe673-69b6-56f9-85fd-2cb8198cfba5', 'ad169c7c-6852-59e6-b997-4cb8575645bb',
   'doyoon.han@openpms.local', 'junho.park@openpms.local', '501f6405-73e1-5d3b-9f81-b89483b26b36', 'agent', now() - interval '3 hours', now() - interval '165 minutes', now() - interval '170 minutes', now() - interval '1 hour', null, true),
  ('모니터 추가 요청', 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb', 'b42e3256-ffde-5d57-a323-43c8be0d70bd', 'e2513703-c41d-5575-aca0-fd084ec26e06',
   'minji.choi@openpms.local', 'haneul.lee@openpms.local', '25629a29-9f0b-50a1-a812-f04018678b83', 'portal', now() - interval '1 day', now() - interval '20 hours', now() - interval '22 hours', now() + interval '2 days', now() - interval '2 hours', false),
  ('메일 용량 증설', 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb', '8201e445-a46a-5135-a972-3f83ee10c3ba', 'e2513703-c41d-5575-aca0-fd084ec26e06',
   'doyoon.han@openpms.local', 'haneul.lee@openpms.local', '25629a29-9f0b-50a1-a812-f04018678b83', 'portal', now() - interval '3 days', now() - interval '2 days', now() - interval '70 hours', now() + interval '1 day', null, false),
  ('프린터 용지 걸림', '2dcd4691-d4a9-5bf0-8908-e4740b42616e', '198d200e-a1ba-5692-ae3c-6ba0bc4319ad', '88fd7a3f-c7d8-5303-af34-54635d6c4430',
   'doyoon.han@openpms.local', null, null, 'portal', now() - interval '5 minutes', now() + interval '4 hours', null, now() + interval '1 day', null, false),
  ('사내 위키 로그인 오류', '2dcd4691-d4a9-5bf0-8908-e4740b42616e', '69baa129-0283-56e0-be38-b73ce5db2b64', 'e2513703-c41d-5575-aca0-fd084ec26e06',
   'minji.choi@openpms.local', 'junho.park@openpms.local', '25629a29-9f0b-50a1-a812-f04018678b83', 'agent', now() - interval '7 days', now() - interval '6 days', now() - interval '167 hours', now() - interval '5 days', null, false)
), w as (
  insert into work_packages (project_id, type_id, status_id, priority_id, subject, author_id, assignee_id, created_at, updated_at, done_ratio)
  select 'a02eb82b-c707-5a81-b0ce-71ea6d523f21', t.type_id::uuid, t.status_id::uuid, t.priority_id::uuid, t.subject, req.id, asg.id, t.created, t.created,
         case when t.status_id in ('8201e445-a46a-5135-a972-3f83ee10c3ba', '69baa129-0283-56e0-be38-b73ce5db2b64') then 100 else 0 end
  from t join users req on req.email = t.requester left join users asg on asg.email = t.assignee
  returning id, subject
)
insert into service_tickets (work_package_id, requester_id, channel, assignment_group_id, sla_policy_id, first_response_due_at, first_responded_at,
                             resolve_due_at, resolved_at, paused_since, resolve_breached, response_breached, requester_mailed_at, requester_mailed_status_id, created_at, updated_at)
select w.id, req.id, t.channel, t.grp::uuid,
       (select p.id from sla_policies p where p.project_id = 'a02eb82b-c707-5a81-b0ce-71ea6d523f21' and p.type_id = t.type_id::uuid and p.priority_id = t.priority_id::uuid),
       t.resp_due, t.responded, t.res_due,
       case when t.status_id in ('8201e445-a46a-5135-a972-3f83ee10c3ba', '69baa129-0283-56e0-be38-b73ce5db2b64') then t.created + interval '1 day' end,
       t.paused, t.breached, false, now(), t.status_id::uuid, t.created, t.created
from w join t on t.subject = w.subject join users req on req.email = t.requester;

-- 작업마다 "만듦" 저널 1건(이력 화면이 비지 않게)
insert into journals (work_package_id, version, user_id, changes, created_at, updated_at)
select w.id, 1, w.author_id, '{"_created": [null, true]}', w.created_at, w.created_at
from work_packages w where w.project_id = 'a02eb82b-c707-5a81-b0ce-71ea6d523f21';
insert into work_package_assignees (work_package_id, user_id, position)
select id, assignee_id, 0 from work_packages where project_id = 'a02eb82b-c707-5a81-b0ce-71ea6d523f21' and assignee_id is not null
on conflict (work_package_id, user_id) do nothing;
