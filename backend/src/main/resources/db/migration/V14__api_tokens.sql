-- 외부 연동 API 토큰(계정 설정 · API 토큰): 사람마다 여러 개. 토큰 원문은 만들 때 한 번만 보이고, DB에는 SHA-256만 둔다.
create table api_tokens (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references users (id) on delete cascade,
  name         text not null check (length(name) between 1 and 60),
  token_hash   bytea not null unique,
  scope        text not null check (scope in ('read', 'write')),   -- read: GET만, write: 그 사람 권한 안의 모든 요청
  created_at   timestamptz not null default now(),
  last_used_at timestamptz,
  expires_at   timestamptz,                                         -- null: 만료 없음
  revoked_at   timestamptz
);
create index api_tokens_user_idx on api_tokens (user_id);
