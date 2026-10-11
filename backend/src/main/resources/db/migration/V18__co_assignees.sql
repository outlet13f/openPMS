-- 공동 담당자 (replica/fixes.md 6번): 작업 하나를 여러 사람이 맡고, 사람마다 예상 시간을 따로 적는다.
-- 맡은 사람 전원이 여기 있고 position 0이 주 담당자다. work_packages.assignee_id는 언제나 position 0의 사람(없으면 null)과
-- 같게 앱이 맞춘다: 정렬·묶기·예전 API는 주 담당자를 보고, 필터·알림·보드·팀 플래너는 이 표의 모든 사람을 본다.
create table work_package_assignees (
  work_package_id uuid not null references work_packages (id) on delete cascade,
  user_id         uuid not null references users (id) on delete cascade,
  position        smallint not null check (position between 0 and 19),
  work_hours      numeric(10, 2) check (work_hours >= 0 and work_hours <= 9999),  -- 이 사람의 예상 시간, 비워 둘 수 있음
  created_at      timestamptz not null default now(),
  primary key (work_package_id, user_id),
  constraint work_package_assignees_position_key unique (work_package_id, position) deferrable initially deferred
);
create index work_package_assignees_user_idx on work_package_assignees (user_id);

-- 지금의 담당자가 첫 담당자
insert into work_package_assignees (work_package_id, user_id, position)
select id, assignee_id, 0 from work_packages where assignee_id is not null;
