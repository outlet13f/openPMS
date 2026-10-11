-- 같은 요청이 두 번 와도 한 번만 처리한다(Idempotency-Key 헤더). 처음 응답을 하루 동안 보관해 같은 키의 재시도에 그대로 돌려준다.
create table idempotency_keys (
  user_id      uuid not null references users (id) on delete cascade,
  key          text not null check (length(key) between 1 and 255),
  method       text not null,
  path         text not null,
  request_hash text not null,          -- 본문 SHA-256: 같은 키로 다른 요청을 보내면 거절
  status       int,                    -- null: 아직 처리 중
  content_type text,
  body         bytea,
  created_at   timestamptz not null default now(),
  primary key (user_id, key)
);
create index idempotency_keys_created_idx on idempotency_keys (created_at);

-- 웹훅 테이블 보완(1.4 점검): 만든 사람 FK 인덱스, updated_at 트리거(V1의 다른 테이블과 같은 규칙)
create index webhooks_created_by_idx on webhooks (created_by);
create trigger webhooks_set_updated_at before update on webhooks for each row execute function set_updated_at();
