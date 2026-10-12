-- 임시 비밀번호 (docs/superpowers/specs/2026-10-12-temporary-password-design.md): 관리자가 메일 없이 사용자를 등록하거나
-- 다시 발급한다. 사용자마다 한 행이고, 행이 있으면 지금 비밀번호(users.password_hash)는 임시라서 첫 로그인 때 바꿔야 한다.
-- 관리자가 전달하도록 평문을 둔다. 비밀번호가 정해지거나 기한이 지나면 앱이 행을 지운다(auth/TemporaryPasswords).
create table temporary_passwords (
  user_id    uuid primary key references users (id) on delete cascade,
  password   text not null,                                   -- API로는 관리 · 사용자의 보기에만 나간다
  expires_at timestamptz not null,
  issued_by  uuid references users (id) on delete set null,
  issued_at  timestamptz not null default now()
);
create index temporary_passwords_issued_by_idx on temporary_passwords (issued_by);
create index temporary_passwords_expires_idx on temporary_passwords (expires_at);

-- 임시 비밀번호로 로그인한 사람에게 주는 일회용 변경 토큰
alter table user_tokens drop constraint user_tokens_purpose_check;
alter table user_tokens add constraint user_tokens_purpose_check check (purpose in ('invitation', 'password_reset', 'password_change'));
