-- 사내 SSO(OpenID Connect). 관리자가 공급자를 등록하고, 사람은 공급자 계정(sub)으로 openPMS 사용자에 연결된다.
create table oidc_providers (
  id            uuid primary key default gen_random_uuid(),
  slug          text not null unique check (slug ~ '^[a-z0-9][a-z0-9-]{0,39}$'),  -- 콜백 주소에 들어가는 식별자
  display_name  text not null check (length(display_name) between 1 and 60),      -- 로그인 버튼 이름
  issuer        text not null,                                                    -- 발견 문서(.well-known/openid-configuration)의 기준 주소
  client_id     text not null,
  client_secret text not null,                                                    -- API로는 내보내지 않는다
  scopes        text not null default 'openid email profile',
  claim_email   text not null default 'email',
  claim_name    text not null default 'name',
  auto_create   boolean not null default false,                                   -- 없는 사람은 처음 로그인할 때 만든다
  enabled       boolean not null default true,
  position      int not null default 0,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create trigger oidc_providers_updated_at before update on oidc_providers for each row execute function set_updated_at();

create table user_identities (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references users(id) on delete cascade,
  provider_id   uuid not null references oidc_providers(id) on delete cascade,     -- 공급자를 지우면 연결만 사라진다
  subject       text not null,
  last_login_at timestamptz,
  created_at    timestamptz not null default now(),
  unique (provider_id, subject),
  unique (provider_id, user_id)
);
create index user_identities_user_idx on user_identities (user_id);
