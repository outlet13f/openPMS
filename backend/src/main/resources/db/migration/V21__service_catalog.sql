-- ITSM 2단계 (docs/superpowers/specs/2026-10-14-itsm-phase2-catalog-design.md): 서비스 카탈로그와 승인.
-- 카탈로그 항목을 고른 요청은 1단계 티켓이고, 승인은 요청마다 만든 ticket_approvals 행으로 단계 순서대로 진행한다(itsm/Approvals).

alter table users add column manager_id uuid references users (id) on delete set null;
alter table users add constraint users_manager_not_self check (manager_id is null or manager_id <> id);
create index users_manager_idx on users (manager_id);

alter table user_groups add column lead_user_id uuid references users (id) on delete set null;
create index user_groups_lead_idx on user_groups (lead_user_id);

create table catalog_categories (
  id         uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects (id) on delete cascade,
  name       text not null check (length(name) between 1 and 100),
  position   int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint catalog_categories_name_key unique (project_id, name)
);
create trigger catalog_categories_set_updated_at before update on catalog_categories for each row execute function set_updated_at();

create table catalog_items (
  id                  uuid primary key default gen_random_uuid(),
  project_id          uuid not null references projects (id) on delete cascade,
  category_id         uuid references catalog_categories (id) on delete set null,
  name                text not null check (length(name) between 1 and 100),
  description_html    text not null default '',
  description_text    text not null default '',
  type_id             uuid not null references types (id) on delete restrict,
  default_priority_id uuid references priorities (id) on delete set null,
  assignment_group_id uuid references user_groups (id) on delete set null,
  is_active           boolean not null default true,
  position            int not null default 0,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  constraint catalog_items_name_key unique (project_id, name)
);
create index catalog_items_category_idx on catalog_items (category_id);
create index catalog_items_type_idx on catalog_items (type_id);
create index catalog_items_priority_idx on catalog_items (default_priority_id);
create index catalog_items_group_idx on catalog_items (assignment_group_id);
create trigger catalog_items_set_updated_at before update on catalog_items for each row execute function set_updated_at();

create table catalog_item_fields (
  catalog_item_id uuid not null references catalog_items (id) on delete cascade,
  custom_field_id uuid not null references custom_fields (id) on delete cascade,
  position        int not null default 0,
  is_required     boolean not null default false,
  primary key (catalog_item_id, custom_field_id)
);
create index catalog_item_fields_field_idx on catalog_item_fields (custom_field_id);

create table catalog_item_approval_steps (
  catalog_item_id uuid not null references catalog_items (id) on delete cascade,
  step_no         smallint not null check (step_no between 1 and 9),
  approver_kind   text not null check (approver_kind in ('requester_manager', 'group_lead')),
  primary key (catalog_item_id, step_no, approver_kind)
);

alter table service_tickets add column catalog_item_id uuid references catalog_items (id) on delete set null;
create index service_tickets_catalog_item_idx on service_tickets (catalog_item_id);

-- 요청마다 만든 승인 행. waiting(뒷단계) → pending(차례) → approved | rejected; 승인자가 없거나 요청자 본인이거나 단계가 끝나면 skipped.
create table ticket_approvals (
  id              uuid primary key default gen_random_uuid(),
  work_package_id uuid not null references work_packages (id) on delete cascade,
  step_no         smallint not null check (step_no between 1 and 9),
  approver_kind   text not null check (approver_kind in ('requester_manager', 'group_lead')),
  approver_id     uuid references users (id) on delete set null,
  decision        text not null check (decision in ('waiting', 'pending', 'approved', 'rejected', 'skipped')),
  skip_reason     text check (skip_reason in ('no_approver', 'requester', 'duplicate', 'step_done', 'left_approval')),
  decided_at      timestamptz,
  comment         text not null default '',
  notified_at     timestamptz,   -- approval-mail이 차례가 온 승인자에게 보낸 때
  reminded_at     timestamptz,   -- 3근무일 지나 다시 보낸 때
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint ticket_approvals_key unique (work_package_id, step_no, approver_kind),
  constraint ticket_approvals_pending_has_approver check (decision not in ('waiting', 'pending', 'approved', 'rejected') or approver_id is not null)
);
create index ticket_approvals_my_pending_idx on ticket_approvals (approver_id) where decision = 'pending';
create index ticket_approvals_approver_idx on ticket_approvals (approver_id);
create trigger ticket_approvals_set_updated_at before update on ticket_approvals for each row execute function set_updated_at();

-- V20처럼 같은 이름의 상태가 이미 있는 설치를 위해, 이름이 겹치는 기존 행을 먼저 바꾼다.
update statuses set name = left(name, 95) || ' (기존)'
where name in ('승인 대기', '반려') and id not in ('3705cad8-6d55-5d45-b841-37385b44f605', '4be1e617-0250-552c-9d28-4bde834daa49');

insert into statuses (id, name, color, position, is_default, is_closed, is_readonly, default_done_ratio, excluded_from_totals, sla_pause) values
  ('3705cad8-6d55-5d45-b841-37385b44f605', '승인 대기', '#5f6a12', 12, false, false, false, 0, false, true),
  ('4be1e617-0250-552c-9d28-4bde834daa49', '반려',      '#c02c39', 13, false, true,  true,  0, true,  false);

-- 처리자는 승인을 건너뛰지 못한다: 승인 대기에서는 취소로만 간다. 반려는 닫힌 상태로 끝난다.
insert into workflows (type_id, role_id, from_status_id, to_status_id, applies_to)
select t.id, r.id, '3705cad8-6d55-5d45-b841-37385b44f605', '8ab8a2d1-0d29-5b49-a263-6284c74a7680', 'always'
from types t cross join roles r
where t.id in ('2dcd4691-d4a9-5bf0-8908-e4740b42616e', 'a3af2ccf-05c6-5b1c-84bc-4d815791e4eb')
  and r.id in ('0329397e-d933-5ac1-b27c-95889ba1a893', '6d4333fb-e975-5213-8ebc-552cca26e1d4', 'a9137727-cb80-51db-98d8-8a9a04f84802')
on conflict do nothing;
