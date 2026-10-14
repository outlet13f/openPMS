-- ITSM 1단계 (docs/superpowers/specs/2026-10-14-itsm-design.md): 장애·서비스 요청을 작업 패키지 유형으로 받고,
-- ITSM 값은 1:1 확장 service_tickets에 둔다. SLA 정책은 프로젝트 + 유형 + 우선순위마다 하나이고 달력(24x7 | business)을 고른다.
-- 요청자는 프로젝트 멤버가 아니어도 포털에서 자기 티켓만 다룬다(itsm/PortalService).

alter table projects drop constraint projects_known_modules;
alter table projects add constraint projects_known_modules check (
  enabled_modules <@ array['work_packages', 'gantt', 'boards', 'calendar', 'team_planner', 'time_tracking', 'service_desk']::text[]);

-- 이 상태에 있는 동안 해결 SLA 시계가 멈춘다(예: 답변 대기)
alter table statuses add column sla_pause boolean not null default false;
-- 포털 접수 양식과 포털 상세에 보이는 사용자 정의 필드
alter table custom_fields add column portal_visible boolean not null default false;

-- 내장 역할 "요청자": 멤버가 아니라 누구에게도 지정하지 않는다. 포털에서 요청자의 상태 전환을 이 역할의 워크플로로 검사한다.
alter table roles drop constraint roles_builtin_check;
alter table roles add constraint roles_builtin_check check (builtin in ('non_member', 'requester'));

create table service_calendars (
  id         uuid primary key default gen_random_uuid(),
  name       text not null unique check (length(name) between 1 and 100),
  mode       text not null check (mode in ('24x7', 'business')),
  time_zone  text not null default 'Asia/Seoul',
  hours      jsonb not null default '[]',   -- [{"day": 1, "start": "09:00", "end": "18:00"}], day는 ISO 요일(1 = 월)
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint service_calendars_hours_array check (jsonb_typeof(hours) = 'array')
);
create trigger service_calendars_set_updated_at before update on service_calendars for each row execute function set_updated_at();

create table sla_policies (
  id                     uuid primary key default gen_random_uuid(),
  project_id             uuid not null references projects (id) on delete cascade,
  type_id                uuid not null references types (id) on delete cascade,
  priority_id            uuid not null references priorities (id) on delete cascade,
  calendar_id            uuid not null references service_calendars (id) on delete restrict,
  first_response_minutes int not null check (first_response_minutes > 0),
  resolve_minutes        int not null check (resolve_minutes > 0),
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  constraint sla_policies_key unique (project_id, type_id, priority_id),
  constraint sla_policies_resolve_after_response check (resolve_minutes >= first_response_minutes)
);
create index sla_policies_type_idx on sla_policies (type_id);
create index sla_policies_priority_idx on sla_policies (priority_id);
create index sla_policies_calendar_idx on sla_policies (calendar_id);
create trigger sla_policies_set_updated_at before update on sla_policies for each row execute function set_updated_at();

create table priority_matrix (
  project_id  uuid not null references projects (id) on delete cascade,
  impact      smallint not null check (impact between 1 and 3),    -- 1 높음 · 2 보통 · 3 낮음
  urgency     smallint not null check (urgency between 1 and 3),
  priority_id uuid not null references priorities (id) on delete cascade,
  primary key (project_id, impact, urgency)
);
create index priority_matrix_priority_idx on priority_matrix (priority_id);

create table service_tickets (
  work_package_id       uuid primary key references work_packages (id) on delete cascade,
  requester_id          uuid not null references users (id) on delete restrict,  -- 사용자는 지우지 않고 익명화
  channel               text not null check (channel in ('portal', 'agent', 'api')),
  impact                smallint not null default 2 check (impact between 1 and 3),
  urgency               smallint not null default 2 check (urgency between 1 and 3),
  assignment_group_id   uuid references user_groups (id) on delete set null,
  sla_policy_id         uuid references sla_policies (id) on delete set null,
  first_response_due_at timestamptz,
  first_responded_at    timestamptz,
  resolve_due_at        timestamptz,
  resolved_at           timestamptz,
  paused_since          timestamptz,
  paused_total_seconds  bigint not null default 0 check (paused_total_seconds >= 0),
  response_breached     boolean not null default false,
  resolve_breached      boolean not null default false,
  response_warned       boolean not null default false,
  resolve_warned        boolean not null default false,
  requester_seen_at     timestamptz,   -- 요청자가 포털 상세를 마지막으로 연 때("새 소식")
  requester_mailed_at   timestamptz,   -- portal-mail이 요청자에게 마지막으로 보낸 때
  requester_mailed_status_id uuid references statuses (id) on delete set null,   -- portal-mail이 요청자에게 마지막으로 알린 상태
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);
create index service_tickets_requester_idx on service_tickets (requester_id);
create index service_tickets_mailed_status_idx on service_tickets (requester_mailed_status_id);
create index service_tickets_group_idx on service_tickets (assignment_group_id);
create index service_tickets_policy_idx on service_tickets (sla_policy_id);
create index service_tickets_open_resolve_due_idx on service_tickets (resolve_due_at) where resolved_at is null;
create index service_tickets_open_response_due_idx on service_tickets (first_response_due_at) where first_responded_at is null;
create trigger service_tickets_set_updated_at before update on service_tickets for each row execute function set_updated_at();

-- 처리자에게 가는 SLA 알림(itsm/SlaJobs). 한 번만 보내는 것은 service_tickets의 *_warned·*_breached가 맡는다.
alter table notifications drop constraint notifications_reason_check;
alter table notifications add constraint notifications_reason_check check (reason in ('mentioned', 'assigned', 'responsible', 'watched', 'shared',
  'created', 'commented', 'status_changed', 'priority_changed', 'dates_changed', 'date_alert_start', 'date_alert_due',
  'date_alert_overdue', 'sla_warning', 'sla_breached'));

alter table webhooks drop constraint webhooks_known_events;
alter table webhooks add constraint webhooks_known_events check (events <@ array['work_package.created', 'work_package.updated',
  'work_package.deleted', 'work_package.commented', 'service_ticket.sla_breached']::text[]);

-- 기본 데이터 ---------------------------------------------------------------
insert into types (id, name, color, position, is_milestone, is_default, is_in_roadmap) values
  ('2dcd4691-d4a9-5bf0-8908-e4740b42616e', '장애',        '#c02c39', 6, false, false, false),
  ('a3af2ccf-05c6-5b1c-84bc-4d815791e4eb', '서비스 요청', '#8b2f8f', 7, false, false, false);

insert into statuses (id, name, color, position, is_default, is_closed, is_readonly, default_done_ratio, excluded_from_totals, sla_pause) values
  ('198d200e-a1ba-5692-ae3c-6ba0bc4319ad', '접수',      '#8b2f8f', 7,  false, false, false, 0,   false, false),
  ('d34fe673-69b6-56f9-85fd-2cb8198cfba5', '처리 중',   '#975800', 8,  false, false, false, 0,   false, false),
  ('b42e3256-ffde-5d57-a323-43c8be0d70bd', '답변 대기', '#8a5300', 9,  false, false, false, 0,   false, true),
  ('8201e445-a46a-5135-a972-3f83ee10c3ba', '해결',      '#1e7a4c', 10, false, true,  false, 100, false, false),
  ('69baa129-0283-56e0-be38-b73ce5db2b64', '종료',      '#5f6873', 11, false, true,  true,  100, false, false);

insert into roles (id, name, scope, builtin, permissions, position) values
  ('a9137727-cb80-51db-98d8-8a9a04f84802', '요청자', 'project', 'requester', '{}', 6);

-- 처리자(프로젝트 관리자·멤버): ITSM 유형에서 ITSM 상태와 취소 사이를 모두 오간다
insert into workflows (type_id, role_id, from_status_id, to_status_id, applies_to)
select t.id, r.id, a.id, b.id, 'always'
from types t cross join roles r cross join statuses a cross join statuses b
where t.id in ('2dcd4691-d4a9-5bf0-8908-e4740b42616e', 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb')
  and r.id in ('0329397e-d933-5ac1-b27c-95889ba1a893', '6d4333fb-e975-5213-8ebc-552cca26e1d4')
  and a.id in ('198d200e-a1ba-5692-ae3c-6ba0bc4319ad', 'd34fe673-69b6-56f9-85fd-2cb8198cfba5', 'b42e3256-ffde-5d57-a323-43c8be0d70bd',
               '8201e445-a46a-5135-a972-3f83ee10c3ba', '69baa129-0283-56e0-be38-b73ce5db2b64', '8ab8a2d1-0d29-5b49-a263-6284c74a7680')
  and b.id in ('198d200e-a1ba-5692-ae3c-6ba0bc4319ad', 'd34fe673-69b6-56f9-85fd-2cb8198cfba5', 'b42e3256-ffde-5d57-a323-43c8be0d70bd',
               '8201e445-a46a-5135-a972-3f83ee10c3ba', '69baa129-0283-56e0-be38-b73ce5db2b64', '8ab8a2d1-0d29-5b49-a263-6284c74a7680')
  and a.id <> b.id
on conflict do nothing;

-- 요청자(작성자 = 요청자일 때): 답변하면 처리 중으로, 해결을 확인(종료)하거나 다시 열기, 끝나지 않은 요청 취소
insert into workflows (type_id, role_id, from_status_id, to_status_id, applies_to)
select t.id, 'a9137727-cb80-51db-98d8-8a9a04f84802', x.from_id::uuid, x.to_id::uuid, 'author'
from types t cross join (values
  ('b42e3256-ffde-5d57-a323-43c8be0d70bd', 'd34fe673-69b6-56f9-85fd-2cb8198cfba5'),
  ('8201e445-a46a-5135-a972-3f83ee10c3ba', 'd34fe673-69b6-56f9-85fd-2cb8198cfba5'),
  ('8201e445-a46a-5135-a972-3f83ee10c3ba', '69baa129-0283-56e0-be38-b73ce5db2b64'),
  ('198d200e-a1ba-5692-ae3c-6ba0bc4319ad', '8ab8a2d1-0d29-5b49-a263-6284c74a7680'),
  ('d34fe673-69b6-56f9-85fd-2cb8198cfba5', '8ab8a2d1-0d29-5b49-a263-6284c74a7680'),
  ('b42e3256-ffde-5d57-a323-43c8be0d70bd', '8ab8a2d1-0d29-5b49-a263-6284c74a7680')) as x(from_id, to_id)
where t.id in ('2dcd4691-d4a9-5bf0-8908-e4740b42616e', 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb');

insert into service_calendars (id, name, mode, hours) values
  ('27ee2635-3f4b-5407-8498-78ce0dbf371c', '평일 근무 시간', 'business',
   '[{"day":1,"start":"09:00","end":"18:00"},{"day":2,"start":"09:00","end":"18:00"},{"day":3,"start":"09:00","end":"18:00"},{"day":4,"start":"09:00","end":"18:00"},{"day":5,"start":"09:00","end":"18:00"}]'),
  ('867264b7-e4ac-5081-b799-9329cc748825', '24시간', '24x7', '[]');

update roles set permissions = permissions || array['service.view', 'service.handle', 'service.manage']
  where id = '0329397e-d933-5ac1-b27c-95889ba1a893' and not ('service.view' = any (permissions));
update roles set permissions = permissions || array['service.view', 'service.handle']
  where id = '6d4333fb-e975-5213-8ebc-552cca26e1d4' and not ('service.view' = any (permissions));
