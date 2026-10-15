-- 개발 데이터: 서비스 카탈로그와 승인(V21 뒤). 관리자·그룹 책임자·분류·항목 3개·승인 대기 요청 1건.
-- 서비스 데스크(a02eb82b-…)와 그룹은 V1004의 고정 ID를 쓴다.

-- 요청자의 관리자: 최민지와 한도윤 모두 alex.kim
update users set manager_id = (select id from users where email = 'alex.kim@openpms.local')
where email in ('minji.choi@openpms.local', 'doyoon.han@openpms.local');

-- 그룹 책임자: 1선 지원 → 박준호, 인프라 → alex.kim
update user_groups set lead_user_id = (select id from users where email = 'junho.park@openpms.local') where id = '25629a29-9f0b-50a1-a812-f04018678b83';
update user_groups set lead_user_id = (select id from users where email = 'alex.kim@openpms.local') where id = '501f6405-73e1-5d3b-9f81-b89483b26b36';

insert into catalog_categories (id, project_id, name, position) values
  ('f6db5c96-0896-5492-a294-28c0cd122fd7', 'a02eb82b-c707-5a81-b0ce-71ea6d523f21', '하드웨어', 1),
  ('9a54c5d6-76be-5e6e-9c7d-040f40dcbc49', 'a02eb82b-c707-5a81-b0ce-71ea6d523f21', '계정·권한', 2);

-- 항목 양식의 사용자 정의 필드: 서비스 요청 유형에서 쓰고 서비스 데스크에만 켠다. 포털 공통 양식에는 나오지 않고(portal_visible = false) 항목 양식으로만 보인다.
insert into custom_fields (id, entity, name, format, is_for_all, is_filterable, position, portal_visible)
values ('3dc367d6-ee91-5c96-b553-b5e3c0d04ac6', 'work_package', '노트북 기종', 'list', false, true, 10, false);
insert into custom_field_options (id, custom_field_id, value, position) values
  ('2e2ddfec-1456-51a9-9a31-bc282a957c5c', '3dc367d6-ee91-5c96-b553-b5e3c0d04ac6', '14인치 표준', 1),
  ('cad4fbf4-575b-5c38-8eaa-41369bf82edb', '3dc367d6-ee91-5c96-b553-b5e3c0d04ac6', '16인치 개발용', 2);
insert into custom_field_types (custom_field_id, type_id) values ('3dc367d6-ee91-5c96-b553-b5e3c0d04ac6', 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb');
insert into project_custom_fields (project_id, custom_field_id) values ('a02eb82b-c707-5a81-b0ce-71ea6d523f21', '3dc367d6-ee91-5c96-b553-b5e3c0d04ac6');

-- 항목: 서비스 요청 유형, 보통 우선순위
insert into catalog_items (id, project_id, category_id, name, description_html, description_text, type_id, default_priority_id, assignment_group_id, position) values
  ('e41eb481-8206-5d78-964b-d4ebb11ca8fd', 'a02eb82b-c707-5a81-b0ce-71ea6d523f21', 'f6db5c96-0896-5492-a294-28c0cd122fd7', '노트북 신청',
   '<p>새 노트북이 필요할 때 신청합니다. 관리자와 그룹 책임자의 승인을 거칩니다.</p>', '새 노트북이 필요할 때 신청합니다. 관리자와 그룹 책임자의 승인을 거칩니다.',
   'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb', 'e2513703-c41d-5575-aca0-fd084ec26e06', '25629a29-9f0b-50a1-a812-f04018678b83', 1),
  ('874a1376-8244-5df2-8b8a-b8da1ce0ad4e', 'a02eb82b-c707-5a81-b0ce-71ea6d523f21', '9a54c5d6-76be-5e6e-9c7d-040f40dcbc49', 'VPN 계정',
   '<p>원격 근무용 VPN 계정을 신청합니다. 관리자의 승인이 필요합니다.</p>', '원격 근무용 VPN 계정을 신청합니다. 관리자의 승인이 필요합니다.',
   'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb', 'e2513703-c41d-5575-aca0-fd084ec26e06', '501f6405-73e1-5d3b-9f81-b89483b26b36', 2),
  ('b187cc86-8e46-5157-981b-2f0c6f66b365', 'a02eb82b-c707-5a81-b0ce-71ea6d523f21', '9a54c5d6-76be-5e6e-9c7d-040f40dcbc49', '공유 폴더 권한',
   '<p>부서 공유 폴더의 읽기·쓰기 권한을 신청합니다. 승인 없이 바로 접수됩니다.</p>', '부서 공유 폴더의 읽기·쓰기 권한을 신청합니다. 승인 없이 바로 접수됩니다.',
   'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb', 'e2513703-c41d-5575-aca0-fd084ec26e06', '25629a29-9f0b-50a1-a812-f04018678b83', 3);
insert into catalog_item_fields (catalog_item_id, custom_field_id, position, is_required)
values ('e41eb481-8206-5d78-964b-d4ebb11ca8fd', '3dc367d6-ee91-5c96-b553-b5e3c0d04ac6', 1, true);
insert into catalog_item_approval_steps (catalog_item_id, step_no, approver_kind) values
  ('e41eb481-8206-5d78-964b-d4ebb11ca8fd', 1, 'requester_manager'),
  ('e41eb481-8206-5d78-964b-d4ebb11ca8fd', 2, 'group_lead'),
  ('874a1376-8244-5df2-8b8a-b8da1ce0ad4e', 1, 'requester_manager');

-- 승인 대기 요청 1건: 최민지의 노트북 신청. 1단계는 alex.kim(요청자 관리자)이 결정할 차례, 2단계는 박준호(그룹 책임자)가 기다림.
-- 요청자 메일은 이미 나간 것으로 둔다(V1004처럼) — portal-mail이 시작하자마자 보내지 않게. approval-mail은 notified_at이 있어 처음 메일을 다시 보내지 않는다.
insert into work_packages (id, project_id, type_id, status_id, priority_id, subject, description_html, author_id, custom_values, created_at, updated_at, done_ratio)
select '26a16197-e272-5e6f-ab22-5b8b7897ac27', 'a02eb82b-c707-5a81-b0ce-71ea6d523f21', 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb',
       '3705cad8-6d55-5d45-b841-37385b44f605', 'e2513703-c41d-5575-aca0-fd084ec26e06', '노트북 신청', '<p>개발용 노트북이 필요합니다.</p>', u.id,
       '{"3dc367d6-ee91-5c96-b553-b5e3c0d04ac6": "cad4fbf4-575b-5c38-8eaa-41369bf82edb"}'::jsonb, now() - interval '2 hours', now() - interval '2 hours', 0
from users u where u.email = 'minji.choi@openpms.local';
insert into service_tickets (work_package_id, requester_id, channel, catalog_item_id, assignment_group_id, sla_policy_id, paused_since,
                             requester_mailed_at, requester_mailed_status_id, created_at, updated_at)
select '26a16197-e272-5e6f-ab22-5b8b7897ac27', u.id, 'portal', 'e41eb481-8206-5d78-964b-d4ebb11ca8fd', '25629a29-9f0b-50a1-a812-f04018678b83',
       (select p.id from sla_policies p where p.project_id = 'a02eb82b-c707-5a81-b0ce-71ea6d523f21' and p.type_id = 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb'
          and p.priority_id = 'e2513703-c41d-5575-aca0-fd084ec26e06'),
       now() - interval '2 hours', now(), '3705cad8-6d55-5d45-b841-37385b44f605', now() - interval '2 hours', now() - interval '2 hours'
from users u where u.email = 'minji.choi@openpms.local';
insert into ticket_approvals (work_package_id, step_no, approver_kind, approver_id, decision, notified_at)
select '26a16197-e272-5e6f-ab22-5b8b7897ac27', 1, 'requester_manager', id, 'pending', now() from users where email = 'alex.kim@openpms.local';
insert into ticket_approvals (work_package_id, step_no, approver_kind, approver_id, decision)
select '26a16197-e272-5e6f-ab22-5b8b7897ac27', 2, 'group_lead', id, 'waiting' from users where email = 'junho.park@openpms.local';
insert into journals (work_package_id, version, user_id, changes, created_at, updated_at)
select w.id, 1, w.author_id, '{"_created": [null, true]}', w.created_at, w.created_at
from work_packages w where w.id = '26a16197-e272-5e6f-ab22-5b8b7897ac27';
