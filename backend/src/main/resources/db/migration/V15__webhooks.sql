-- 웹훅(관리 · API와 웹훅): 작업 이벤트를 다른 시스템의 주소로 알린다. 보낼 일은 webhook_deliveries에 쌓고 예약 작업이 보낸다.
create table webhooks (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (length(name) between 1 and 100),
  url         text not null check (url ~ '^https?://'),
  secret      text not null,                                   -- HMAC-SHA256 서명 키. API로는 내보내지 않는다
  events      text[] not null default '{work_package.created,work_package.updated,work_package.deleted,work_package.commented}',
  project_ids uuid[],                                          -- null: 모든 프로젝트
  enabled     boolean not null default true,
  created_by  uuid references users (id) on delete set null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  constraint webhooks_known_events check (
    events <@ array['work_package.created', 'work_package.updated', 'work_package.deleted', 'work_package.commented']::text[])
);

create table webhook_deliveries (
  id              uuid primary key default gen_random_uuid(),
  webhook_id      uuid not null references webhooks (id) on delete cascade,
  event           text not null,
  payload         jsonb not null,
  status          text not null default 'pending' check (status in ('pending', 'succeeded', 'failed')),
  attempts        int not null default 0,
  next_attempt_at timestamptz not null default now(),
  response_status int,
  error           text,
  created_at      timestamptz not null default now(),
  delivered_at    timestamptz
);
create index webhook_deliveries_due_idx on webhook_deliveries (next_attempt_at) where status = 'pending';
create index webhook_deliveries_webhook_idx on webhook_deliveries (webhook_id, created_at desc);
