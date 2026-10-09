-- 작업 공유(S07/S08 공유): 프로젝트 멤버가 아니어도 작업 하나를 보기·코멘트·편집 권한으로 받는다.
-- 공유 역할의 권한: view = wp.view / comment = + wp.comment, wp.edit_own_comments / edit = + wp.edit, wp.attach
create table work_package_shares (
  id              uuid primary key default gen_random_uuid(),
  work_package_id uuid not null references work_packages (id) on delete cascade,
  user_id         uuid not null references users (id) on delete cascade,
  role            text not null check (role in ('view', 'comment', 'edit')),
  created_by      uuid references users (id) on delete set null,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint work_package_shares_one_per_user unique (work_package_id, user_id)
);
create index work_package_shares_user_idx on work_package_shares (user_id);
create index work_package_shares_created_by_idx on work_package_shares (created_by);
create trigger work_package_shares_set_updated_at before update on work_package_shares for each row execute function set_updated_at();

-- 새 권한 wp.share(작업 공유 관리): 기본 '프로젝트 관리자' 역할에 준다
update roles set permissions = array_append(permissions, 'wp.share')
where id = '0329397e-d933-5ac1-b27c-95889ba1a893' and not ('wp.share' = any (permissions));
