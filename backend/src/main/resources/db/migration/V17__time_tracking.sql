-- 시간 기록(공수): 작업에 쓴 시간을 사람·날짜·활동별로 남긴다. 주간 공수 입력표와 공수 보고서가 이 표를 읽는다.
create table time_activities (
  id         uuid primary key default gen_random_uuid(),
  name       text not null unique check (length(name) between 1 and 60),
  position   int not null default 0,
  is_default boolean not null default false,   -- 입력표에서 활동을 고르지 않으면 이것
  active     boolean not null default true,    -- 끈 활동은 새 기록에 못 쓰고, 지난 기록에는 남는다
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index time_activities_one_default on time_activities (is_default) where is_default;
create trigger time_activities_set_updated_at before update on time_activities for each row execute function set_updated_at();

insert into time_activities (name, position, is_default) values
  ('개발', 1, true), ('설계', 2, false), ('회의', 3, false), ('테스트', 4, false), ('문서', 5, false), ('관리', 6, false), ('기타', 7, false);

create table time_entries (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references users (id) on delete cascade,
  project_id      uuid not null references projects (id) on delete cascade,   -- 작업의 프로젝트(작업을 옮기면 같이 바뀜)
  work_package_id uuid not null references work_packages (id) on delete cascade,
  activity_id     uuid references time_activities (id) on delete set null,
  spent_on        date not null,
  hours           numeric(5, 2) not null check (hours > 0 and hours <= 24),
  comment         text not null default '' check (length(comment) <= 1000),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
create index time_entries_user_day_idx on time_entries (user_id, spent_on);
create index time_entries_project_day_idx on time_entries (project_id, spent_on);
create index time_entries_work_package_idx on time_entries (work_package_id);
create index time_entries_activity_idx on time_entries (activity_id);
create trigger time_entries_set_updated_at before update on time_entries for each row execute function set_updated_at();

-- 권한: 프로젝트 관리자는 모두, 멤버는 자기 시간 기록과 보기
update roles set permissions = permissions || array['time.log', 'time.view', 'time.manage']
  where id = '0329397e-d933-5ac1-b27c-95889ba1a893' and not ('time.log' = any (permissions));
update roles set permissions = permissions || array['time.log', 'time.view']
  where id = '6d4333fb-e975-5213-8ebc-552cca26e1d4' and not ('time.log' = any (permissions));

-- 모듈: 알려진 모듈에 time_tracking을 더하고, 지금 있는 프로젝트에서 켜 둔다(프로젝트 설정 · 모듈에서 끌 수 있음)
alter table projects drop constraint projects_known_modules;
alter table projects add constraint projects_known_modules check (
  enabled_modules <@ array['work_packages', 'gantt', 'boards', 'calendar', 'team_planner', 'time_tracking']::text[]);
update projects set enabled_modules = array_append(enabled_modules, 'time_tracking') where not ('time_tracking' = any (enabled_modules));
