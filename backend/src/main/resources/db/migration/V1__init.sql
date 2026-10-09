-- openPMS 스키마 v1 (PostgreSQL 17)
-- replica/recon.md의 추론 데이터 모델을 클린룸으로 설계한 것. Flyway V1__init.sql로 그대로 쓴다.
--
-- 규칙
--   * 모든 엔터티 테이블: id uuid PK(gen_random_uuid), created_at, updated_at (timestamptz, UTC)
--     조인 테이블·1:1 테이블은 복합 PK 또는 공유 PK를 쓰고 created_at만 둔다(1:1은 updated_at도).
--   * 일정 날짜(시작·완료·휴무일)는 "달력 날짜"라서 date 타입. 시각은 전부 timestamptz.
--   * 상태성 컬럼은 check 제약. on delete 규칙은 FK마다 명시.
--   * 권한은 RLS가 아니라 데이터 계층(Spring 서비스 + jOOQ 조건)에서 검사한다. architecture.md 참고.
--   * 라이브러리 테이블(spring_session*, scheduled_tasks)은 각 라이브러리의 PostgreSQL 스키마를
--     별도 마이그레이션(V2)으로 추가한다. 여기에는 없다.
--   * PostgreSQL 18로 올리면 gen_random_uuid() 대신 uuidv7()(시간순)을 쓰는 것을 검토.

create extension if not exists pg_trgm;   -- 한국어 부분 일치 검색(ILIKE)용 트라이그램 인덱스
create extension if not exists citext;    -- 대소문자 무시 이메일

-- updated_at 자동 갱신 (파일 맨 끝에서 updated_at 컬럼이 있는 모든 테이블에 트리거를 건다)
create or replace function set_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end
$$;

-- =====================================================================
-- 사용자·인증
-- =====================================================================

create table users (
  id                 uuid primary key default gen_random_uuid(),
  email              citext not null,
  name               text not null check (length(name) between 1 and 100),  -- 한국어 이름은 성/이름 분리 없이 한 칸
  password_hash      text,                       -- null: 초대 수락 전, 또는 이후 SSO 전용 계정
  status             text not null default 'active'
                       check (status in ('invited', 'active', 'locked', 'deleted')),
  is_admin           boolean not null default false,
  locale             text not null default 'ko' check (locale in ('ko', 'en')),
  time_zone          text not null default 'Asia/Seoul',
  theme              text not null default 'system' check (theme in ('system', 'light', 'dark')),
  failed_login_count int not null default 0 check (failed_login_count >= 0),
  locked_until       timestamptz,               -- 로그인 실패 누적 시 일시 잠금
  last_login_at      timestamptz,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  constraint users_email_key unique (email),
  constraint users_deleted_has_no_password check (status <> 'deleted' or password_hash is null)
);
create index users_status_idx on users (status);
create index users_name_trgm on users using gin (name gin_trgm_ops);  -- 멘션·담당자 자동완성

create table user_preferences (
  user_id                uuid primary key references users (id) on delete cascade,
  comments_newest_first  boolean not null default false,
  warn_on_unsaved        boolean not null default true,
  keyboard_shortcuts     boolean not null default true,
  digest_enabled         boolean not null default true,
  digest_times           time[] not null default '{08:00}',   -- 사용자 시간대 기준
  immediate_on_mention   boolean not null default true,
  reminders_paused_until date,
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  constraint user_preferences_digest_times_count check (cardinality(digest_times) between 1 and 4)
);

-- 초대·비밀번호 재설정 토큰. 원문 토큰은 메일에만 있고 DB에는 SHA-256 해시만 저장
create table user_tokens (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references users (id) on delete cascade,
  purpose     text not null check (purpose in ('invitation', 'password_reset')),
  token_hash  bytea not null unique,
  expires_at  timestamptz not null,
  used_at     timestamptz,
  created_by  uuid references users (id) on delete set null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index user_tokens_user_idx on user_tokens (user_id);
create index user_tokens_created_by_idx on user_tokens (created_by);
create index user_tokens_expires_idx on user_tokens (expires_at);

create table user_groups (
  id          uuid primary key default gen_random_uuid(),
  name        text not null unique check (length(name) between 1 and 100),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table user_group_members (
  group_id    uuid not null references user_groups (id) on delete cascade,
  user_id     uuid not null references users (id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (group_id, user_id)
);
create index user_group_members_user_idx on user_group_members (user_id);

-- 역할: permissions는 앱이 정의한 권한 키 목록(architecture.md "권한 키")
create table roles (
  id          uuid primary key default gen_random_uuid(),
  name        text not null unique check (length(name) between 1 and 100),
  scope       text not null check (scope in ('project', 'global')),
  builtin     text check (builtin in ('non_member')),  -- 공개 프로젝트를 멤버 아닌 사용자가 볼 때 쓰는 역할
  permissions text[] not null default '{}',
  position    int not null default 0,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  constraint roles_builtin_is_project check (builtin is null or scope = 'project')
);
create unique index roles_one_per_builtin on roles (builtin) where builtin is not null;

create table global_role_assignments (
  user_id     uuid not null references users (id) on delete cascade,
  role_id     uuid not null references roles (id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (user_id, role_id)
);
create index global_role_assignments_role_idx on global_role_assignments (role_id);

-- =====================================================================
-- 프로젝트·멤버
-- =====================================================================

create table projects (
  id                 uuid primary key default gen_random_uuid(),
  identifier         text not null unique check (identifier ~ '^[a-z][a-z0-9-]{0,99}$'),  -- URL용 slug
  name               text not null check (length(name) between 1 and 255),
  description_html   text not null default '',   -- 서버에서 sanitize한 HTML
  description_text   text not null default '',   -- 검색용 평문
  status             text check (status in ('on_track', 'at_risk', 'off_track')),
  status_explanation text not null default '',
  is_public          boolean not null default false,
  is_template        boolean not null default false,
  archived_at        timestamptz,
  parent_id          uuid references projects (id) on delete restrict,  -- 하위 프로젝트가 있으면 삭제 불가
  enabled_modules    text[] not null default '{work_packages,gantt,boards}',
  custom_values      jsonb not null default '{}',  -- 프로젝트 특성: {"<custom_field_id>": 값}
  created_by         uuid references users (id) on delete set null,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  constraint projects_not_own_parent check (parent_id <> id),
  constraint projects_known_modules check (
    enabled_modules <@ array['work_packages', 'gantt', 'boards', 'calendar', 'team_planner']::text[]),
  constraint projects_custom_values_object check (jsonb_typeof(custom_values) = 'object')
);
create index projects_parent_idx on projects (parent_id);
create index projects_created_by_idx on projects (created_by);
create index projects_active_idx on projects (name) where archived_at is null;
create index projects_name_trgm on projects using gin (name gin_trgm_ops);

create table project_favorites (
  user_id     uuid not null references users (id) on delete cascade,
  project_id  uuid not null references projects (id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (user_id, project_id)
);
create index project_favorites_project_idx on project_favorites (project_id);

-- 멤버는 사용자 또는 그룹. 그룹 멤버십의 사용자 권한은 조회 시점에 합산한다(파생 행을 만들지 않음)
create table members (
  id          uuid primary key default gen_random_uuid(),
  project_id  uuid not null references projects (id) on delete cascade,
  user_id     uuid references users (id) on delete cascade,
  group_id    uuid references user_groups (id) on delete cascade,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  constraint members_one_principal check (num_nonnulls(user_id, group_id) = 1)
);
create unique index members_project_user_key on members (project_id, user_id) where user_id is not null;
create unique index members_project_group_key on members (project_id, group_id) where group_id is not null;
create index members_project_idx on members (project_id);
create index members_user_idx on members (user_id);
create index members_group_idx on members (group_id);

create table member_roles (
  member_id   uuid not null references members (id) on delete cascade,
  role_id     uuid not null references roles (id) on delete restrict,  -- 쓰고 있는 역할은 삭제 불가
  created_at  timestamptz not null default now(),
  primary key (member_id, role_id)
);
create index member_roles_role_idx on member_roles (role_id);

-- =====================================================================
-- 작업 패키지 설정: 유형·상태·워크플로·우선순위·버전·카테고리·사용자 정의 필드
-- =====================================================================

create table types (
  id                        uuid primary key default gen_random_uuid(),
  name                      text not null unique check (length(name) between 1 and 100),
  color                     text not null default '#1f5fc4' check (color ~ '^#[0-9A-Fa-f]{6}$'),
  position                  int not null default 0,
  is_milestone              boolean not null default false,
  is_default                boolean not null default false,  -- 새 프로젝트에서 자동 활성
  is_in_roadmap             boolean not null default true,
  attribute_groups          jsonb not null default '[]',     -- [{"name": "...", "attributes": ["assignee", ...]}]
  description_template_html text not null default '',
  created_at                timestamptz not null default now(),
  updated_at                timestamptz not null default now(),
  constraint types_attribute_groups_array check (jsonb_typeof(attribute_groups) = 'array')
);

create table project_types (
  project_id  uuid not null references projects (id) on delete cascade,
  type_id     uuid not null references types (id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (project_id, type_id)
);
create index project_types_type_idx on project_types (type_id);

create table statuses (
  id                   uuid primary key default gen_random_uuid(),
  name                 text not null unique check (length(name) between 1 and 100),
  color                text not null default '#5f6873' check (color ~ '^#[0-9A-Fa-f]{6}$'),
  position             int not null default 0,
  is_default           boolean not null default false,
  is_closed            boolean not null default false,
  is_readonly          boolean not null default false,  -- 이 상태에서는 상태 외 필드 편집 불가
  default_done_ratio   smallint not null default 0 check (default_done_ratio between 0 and 100),
  excluded_from_totals boolean not null default false,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now()
);
create unique index statuses_one_default on statuses (is_default) where is_default;
create index statuses_closed_idx on statuses (is_closed);

-- 허용된 상태 전환. applies_to: always(모두) | author(작성자일 때만) | assignee(담당자일 때만)
create table workflows (
  id              uuid primary key default gen_random_uuid(),
  type_id         uuid not null references types (id) on delete cascade,
  role_id         uuid not null references roles (id) on delete cascade,
  from_status_id  uuid not null references statuses (id) on delete cascade,
  to_status_id    uuid not null references statuses (id) on delete cascade,
  applies_to      text not null default 'always' check (applies_to in ('always', 'author', 'assignee')),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint workflows_transition_key unique (type_id, role_id, from_status_id, to_status_id, applies_to),
  constraint workflows_real_transition check (from_status_id <> to_status_id)
);
create index workflows_role_idx on workflows (role_id);
create index workflows_from_status_idx on workflows (from_status_id);
create index workflows_to_status_idx on workflows (to_status_id);

create table priorities (
  id          uuid primary key default gen_random_uuid(),
  name        text not null unique check (length(name) between 1 and 100),
  color       text not null default '#5f6873' check (color ~ '^#[0-9A-Fa-f]{6}$'),
  position    int not null default 0,
  is_default  boolean not null default false,
  is_active   boolean not null default true,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create unique index priorities_one_default on priorities (is_default) where is_default;

create table versions (
  id          uuid primary key default gen_random_uuid(),
  project_id  uuid not null references projects (id) on delete cascade,
  name        text not null check (length(name) between 1 and 100),
  description text not null default '',
  status      text not null default 'open' check (status in ('open', 'locked', 'closed')),
  sharing     text not null default 'none'
                check (sharing in ('none', 'descendants', 'hierarchy', 'tree', 'system')),
  start_date  date,
  end_date    date,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  constraint versions_project_name_key unique (project_id, name),
  constraint versions_dates_ordered check (start_date is null or end_date is null or end_date >= start_date)
);
create index versions_status_idx on versions (status);
create index versions_sharing_idx on versions (sharing) where sharing <> 'none';

create table categories (
  id                  uuid primary key default gen_random_uuid(),
  project_id          uuid not null references projects (id) on delete cascade,
  name                text not null check (length(name) between 1 and 100),
  default_assignee_id uuid references users (id) on delete set null,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  constraint categories_project_name_key unique (project_id, name)
);
create index categories_default_assignee_idx on categories (default_assignee_id);

create table custom_fields (
  id             uuid primary key default gen_random_uuid(),
  entity         text not null check (entity in ('work_package', 'project', 'user')),
  name           text not null check (length(name) between 1 and 100),
  format         text not null
                   check (format in ('string', 'text', 'integer', 'float', 'list', 'date',
                                     'bool', 'user', 'version', 'link')),
  is_required    boolean not null default false,
  is_for_all     boolean not null default false,  -- true면 모든 프로젝트에서 활성
  is_filterable  boolean not null default true,
  is_searchable  boolean not null default false,
  is_multi_value boolean not null default false,
  default_value  jsonb,
  min_length     int check (min_length >= 0),
  max_length     int check (max_length > 0),
  regexp         text,
  position       int not null default 0,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint custom_fields_entity_name_key unique (entity, name),
  constraint custom_fields_multi_only_for_refs check (not is_multi_value or format in ('list', 'user', 'version')),
  constraint custom_fields_length_ordered check (min_length is null or max_length is null or max_length >= min_length)
);

create table custom_field_options (
  id              uuid primary key default gen_random_uuid(),
  custom_field_id uuid not null references custom_fields (id) on delete cascade,
  value           text not null check (length(value) between 1 and 255),
  position        int not null default 0,
  is_default      boolean not null default false,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint custom_field_options_value_key unique (custom_field_id, value)
);

-- 작업 패키지 사용자 정의 필드를 어떤 유형에서 쓰는지
create table custom_field_types (
  custom_field_id uuid not null references custom_fields (id) on delete cascade,
  type_id         uuid not null references types (id) on delete cascade,
  created_at      timestamptz not null default now(),
  primary key (custom_field_id, type_id)
);
create index custom_field_types_type_idx on custom_field_types (type_id);

-- is_for_all이 아닌 필드를 켠 프로젝트
create table project_custom_fields (
  project_id      uuid not null references projects (id) on delete cascade,
  custom_field_id uuid not null references custom_fields (id) on delete cascade,
  created_at      timestamptz not null default now(),
  primary key (project_id, custom_field_id)
);
create index project_custom_fields_field_idx on project_custom_fields (custom_field_id);

-- =====================================================================
-- 작업 패키지
-- =====================================================================

-- 화면에 보이는 "#123" 번호. 인스턴스 전체에서 하나의 순번(프로젝트를 옮겨도 번호 유지)
create sequence work_package_number_seq;

create table work_packages (
  id                      uuid primary key default gen_random_uuid(),
  number                  bigint not null unique default nextval('work_package_number_seq'),
  project_id              uuid not null references projects (id) on delete cascade,
  type_id                 uuid not null references types (id) on delete restrict,
  status_id               uuid not null references statuses (id) on delete restrict,
  priority_id             uuid not null references priorities (id) on delete restrict,
  subject                 text not null check (length(subject) between 1 and 255),
  description_html        text not null default '',
  description_text        text not null default '',
  author_id               uuid not null references users (id) on delete restrict,  -- 사용자는 지우지 않고 익명화
  assignee_id             uuid references users (id) on delete set null,
  responsible_id          uuid references users (id) on delete set null,          -- 화면의 "담당(책임자)"
  version_id              uuid references versions (id) on delete set null,
  category_id             uuid references categories (id) on delete set null,
  -- 단건 삭제는 앱이 자식을 먼저 처리한다. NO ACTION이라 프로젝트 삭제 cascade 때 한 문장 안에서 같이 지워진다
  parent_id               uuid references work_packages (id) on delete no action,

  -- 일정 (date = 달력 날짜, 시간대 없음)
  start_date              date,
  due_date                date,
  duration                int check (duration > 0),                 -- 근무일 수(ignore_non_working_days면 달력일 수)
  ignore_non_working_days boolean not null default false,          -- 화면의 "근무일만" 체크 해제
  schedule_manually       boolean not null default true,           -- false = 자동(선행·자식에 따라 날짜 결정)

  -- 진행률 (작업량은 시간 단위)
  work_hours              numeric(10, 2) check (work_hours >= 0),
  remaining_hours         numeric(10, 2) check (remaining_hours >= 0),
  done_ratio              smallint check (done_ratio between 0 and 100),
  total_work_hours        numeric(12, 2),   -- 자신 + 자손 합계(파생값, 앱이 갱신)
  total_remaining_hours   numeric(12, 2),
  total_done_ratio        smallint check (total_done_ratio between 0 and 100),

  custom_values           jsonb not null default '{}',   -- {"<custom_field_id>": 값 | [값...]}
  lock_version            int not null default 0,        -- 낙관적 잠금: UPDATE ... WHERE lock_version = ?
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),

  constraint wp_not_own_parent check (parent_id <> id),
  constraint wp_dates_ordered check (start_date is null or due_date is null or due_date >= start_date),
  constraint wp_remaining_le_work check (remaining_hours is null or work_hours is null or remaining_hours <= work_hours),
  constraint wp_custom_values_object check (jsonb_typeof(custom_values) = 'object')
);
create index wp_project_status_idx on work_packages (project_id, status_id);
create index wp_type_idx on work_packages (type_id);
create index wp_status_idx on work_packages (status_id);
create index wp_priority_idx on work_packages (priority_id);
create index wp_author_idx on work_packages (author_id);
create index wp_assignee_idx on work_packages (assignee_id);
create index wp_responsible_idx on work_packages (responsible_id);
create index wp_version_idx on work_packages (version_id);
create index wp_category_idx on work_packages (category_id);
create index wp_parent_idx on work_packages (parent_id);
create index wp_start_date_idx on work_packages (start_date);
create index wp_due_date_idx on work_packages (due_date);
create index wp_created_at_idx on work_packages (created_at);
create index wp_updated_at_idx on work_packages (updated_at);
create index wp_subject_trgm on work_packages using gin (subject gin_trgm_ops);
create index wp_description_trgm on work_packages using gin (description_text gin_trgm_ops);
create index wp_custom_values_gin on work_packages using gin (custom_values jsonb_path_ops);

-- 관계는 정방향 6종만 저장한다. follows·duplicated·blocked·partof·required는 화면에서 보는 역방향 이름.
-- 부모-자식은 관계가 아니라 work_packages.parent_id.
create table relations (
  id          uuid primary key default gen_random_uuid(),
  from_id     uuid not null references work_packages (id) on delete cascade,
  to_id       uuid not null references work_packages (id) on delete cascade,
  type        text not null
                check (type in ('relates', 'precedes', 'duplicates', 'blocks', 'includes', 'requires')),
  lag         int not null default 0 check (lag >= 0),   -- 근무일. precedes에만 의미 있음
  description text not null default '',
  created_by  uuid references users (id) on delete set null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  constraint relations_not_self check (from_id <> to_id),
  constraint relations_lag_only_precedes check (lag = 0 or type = 'precedes')
);
-- 같은 두 작업 사이에는 관계를 하나만 (방향 무관)
create unique index relations_one_per_pair on relations (least(from_id, to_id), greatest(from_id, to_id));
create index relations_from_idx on relations (from_id, type);
create index relations_to_idx on relations (to_id, type);
create index relations_created_by_idx on relations (created_by);

create table watchers (
  work_package_id uuid not null references work_packages (id) on delete cascade,
  user_id         uuid not null references users (id) on delete cascade,
  created_at      timestamptz not null default now(),
  primary key (work_package_id, user_id)
);
create index watchers_user_idx on watchers (user_id);

create table attachments (
  id              uuid primary key default gen_random_uuid(),
  -- null: 업로드는 됐지만 아직 작업 패키지에 연결되지 않음(생성 폼 작성 중). 정리 작업이 24시간 후 삭제
  work_package_id uuid references work_packages (id) on delete cascade,
  file_name       text not null check (length(file_name) between 1 and 255),
  content_type    text not null,
  byte_size       bigint not null check (byte_size > 0),
  sha256          bytea not null,
  storage_key     text not null unique,      -- 파일 저장소의 키(경로). 원본 파일명은 경로에 쓰지 않음
  author_id       uuid not null references users (id) on delete restrict,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
create index attachments_wp_idx on attachments (work_package_id);
create index attachments_author_idx on attachments (author_id);
create index attachments_orphans_idx on attachments (created_at) where work_package_id is null;
create index attachments_file_name_trgm on attachments using gin (file_name gin_trgm_ops);

-- 활동 저널: 생성·변경·코멘트를 한 줄씩. 같은 사용자의 연속 변경은 집계 시간(기본 5분) 안이면 합친다
create table journals (
  id              uuid primary key default gen_random_uuid(),
  work_package_id uuid not null references work_packages (id) on delete cascade,
  version         int not null check (version >= 1),
  user_id         uuid not null references users (id) on delete restrict,
  notes_html      text not null default '',
  notes_text      text not null default '',
  is_internal     boolean not null default false,          -- 내부 코멘트(권한 있는 역할만 열람)
  changes         jsonb not null default '{}',             -- {"status_id": [이전, 이후], ...}
  cause           jsonb,                                   -- 자동 변경 원인 {"kind": "predecessor_moved", "work_package_number": 12}
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),      -- 코멘트 수정 시각
  constraint journals_version_key unique (work_package_id, version),
  constraint journals_changes_object check (jsonb_typeof(changes) = 'object')
);
create index journals_user_idx on journals (user_id);
create index journals_wp_created_idx on journals (work_package_id, created_at);
create index journals_notes_trgm on journals using gin (notes_text gin_trgm_ops);  -- "코멘트" 필터

-- =====================================================================
-- 저장된 보기·보드
-- =====================================================================

-- 기본 보기(모두 열림, 내게 할당됨 등)는 앱 코드에 있고 행으로 저장하지 않는다
create table queries (
  id          uuid primary key default gen_random_uuid(),
  project_id  uuid references projects (id) on delete cascade,   -- null: 전역 보기
  owner_id    uuid not null references users (id) on delete cascade,
  name        text not null check (length(name) between 1 and 255),
  view        text not null default 'table' check (view in ('table', 'gantt', 'calendar', 'team_planner')),
  is_public   boolean not null default false,
  spec        jsonb not null,   -- {"v":1, filters, columns, sort, groupBy, hierarchy, sums, includeSubprojects, timeline, ...}
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  constraint queries_spec_object check (jsonb_typeof(spec) = 'object')
);
create index queries_project_idx on queries (project_id, view);
create index queries_owner_idx on queries (owner_id);

create table query_favorites (
  user_id     uuid not null references users (id) on delete cascade,
  query_id    uuid not null references queries (id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (user_id, query_id)
);
create index query_favorites_query_idx on query_favorites (query_id);

create table boards (
  id          uuid primary key default gen_random_uuid(),
  project_id  uuid not null references projects (id) on delete cascade,
  name        text not null check (length(name) between 1 and 255),
  kind        text not null
                check (kind in ('basic', 'status', 'assignee', 'version', 'subproject', 'parent_child')),
  is_public   boolean not null default true,
  filters     jsonb not null default '[]',   -- 보드 전체에 걸리는 필터(쿼리 엔진 형식)
  created_by  uuid references users (id) on delete set null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  constraint boards_filters_array check (jsonb_typeof(filters) = 'array')
);
create index boards_project_idx on boards (project_id);
create index boards_created_by_idx on boards (created_by);

-- 컬럼은 보드 종류에 맞는 값 하나만 가진다. 값이 삭제되면 컬럼도 사라진다
create table board_columns (
  id                     uuid primary key default gen_random_uuid(),
  board_id               uuid not null references boards (id) on delete cascade,
  position               int not null,
  name                   text check (length(name) between 1 and 100),                 -- basic
  status_id              uuid references statuses (id) on delete cascade,             -- status
  user_id                uuid references users (id) on delete cascade,                -- assignee
  version_id             uuid references versions (id) on delete cascade,             -- version
  subproject_id          uuid references projects (id) on delete cascade,             -- subproject
  parent_work_package_id uuid references work_packages (id) on delete cascade,        -- parent_child
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  constraint board_columns_one_value check (
    num_nonnulls(name, status_id, user_id, version_id, subproject_id, parent_work_package_id) = 1),
  constraint board_columns_position_key unique (board_id, position) deferrable initially deferred
);
create index board_columns_status_idx on board_columns (status_id);
create index board_columns_user_idx on board_columns (user_id);
create index board_columns_version_idx on board_columns (version_id);
create index board_columns_subproject_idx on board_columns (subproject_id);
create index board_columns_parent_wp_idx on board_columns (parent_work_package_id);

-- 카드 순서. basic 보드는 column_id가 소속, 나머지 보드는 소속을 속성으로 계산하고 순서만 저장
create table board_cards (
  board_id        uuid not null references boards (id) on delete cascade,
  work_package_id uuid not null references work_packages (id) on delete cascade,
  column_id       uuid references board_columns (id) on delete cascade,
  position        double precision not null,   -- 사이에 끼워 넣기용 실수 순번
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  primary key (board_id, work_package_id)
);
create index board_cards_wp_idx on board_cards (work_package_id);
create index board_cards_column_idx on board_cards (column_id, position);

-- =====================================================================
-- 알림
-- =====================================================================

create table notifications (
  id              uuid primary key default gen_random_uuid(),
  recipient_id    uuid not null references users (id) on delete cascade,
  work_package_id uuid not null references work_packages (id) on delete cascade,
  project_id      uuid not null references projects (id) on delete cascade,  -- 프로젝트 필터용 비정규화
  journal_id      uuid references journals (id) on delete cascade,           -- 날짜 알림은 null
  actor_id        uuid references users (id) on delete set null,
  reason          text not null
                    check (reason in ('mentioned', 'assigned', 'responsible', 'watched', 'shared',
                                      'created', 'commented', 'status_changed', 'priority_changed',
                                      'dates_changed', 'date_alert_start', 'date_alert_due',
                                      'date_alert_overdue')),
  alert_date      date,            -- 날짜 알림의 기준일(중복 방지 키)
  read_at         timestamptz,
  emailed_at      timestamptz,     -- 이메일(즉시·요약)로 보낸 시각
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint notifications_alert_date_iff_date_alert check ((reason like 'date_alert%') = (alert_date is not null))
);
create index notifications_inbox_unread on notifications (recipient_id, created_at desc) where read_at is null;
create index notifications_inbox_all on notifications (recipient_id, created_at desc);
create index notifications_mail_pending on notifications (recipient_id, created_at) where emailed_at is null;
create index notifications_wp_idx on notifications (work_package_id);
create index notifications_project_idx on notifications (project_id);
create index notifications_journal_idx on notifications (journal_id);
create index notifications_actor_idx on notifications (actor_id);
create unique index notifications_one_date_alert
  on notifications (recipient_id, work_package_id, reason, alert_date) where alert_date is not null;

-- project_id null = 사용자 기본값. 멘션·주시 알림은 항상 켜져 있어 컬럼이 없다
create table notification_settings (
  id                    uuid primary key default gen_random_uuid(),
  user_id               uuid not null references users (id) on delete cascade,
  project_id            uuid references projects (id) on delete cascade,
  assignee              boolean not null default true,
  responsible           boolean not null default true,
  shared                boolean not null default true,
  work_package_created  boolean not null default false,
  commented             boolean not null default false,
  status_changed        boolean not null default false,
  priority_changed      boolean not null default false,
  dates_changed         boolean not null default false,
  start_date_alert_days smallint check (start_date_alert_days in (0, 1, 3, 7)),  -- null = 끔
  due_date_alert_days   smallint check (due_date_alert_days in (0, 1, 3, 7)),
  overdue_alert         boolean not null default false,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  constraint notification_settings_scope_key unique nulls not distinct (user_id, project_id)
);
create index notification_settings_project_idx on notification_settings (project_id);

-- =====================================================================
-- 달력·전역 설정
-- =====================================================================

create table non_working_days (
  id          uuid primary key default gen_random_uuid(),
  day         date not null unique,
  name        text not null check (length(name) between 1 and 100),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

-- 키-값 설정. 키 목록과 값 형식은 architecture.md "전역 설정"
create table settings (
  key         text primary key check (key ~ '^[a-z][a-z0-9_.]*$'),
  value       jsonb not null,
  updated_by  uuid references users (id) on delete set null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index settings_updated_by_idx on settings (updated_by);

-- =====================================================================
-- updated_at 트리거: updated_at 컬럼이 있는 모든 테이블
-- =====================================================================
do $$
declare
  t text;
begin
  for t in
    select c.table_name
    from information_schema.columns c
    join information_schema.tables tb
      on tb.table_schema = c.table_schema and tb.table_name = c.table_name and tb.table_type = 'BASE TABLE'
    where c.table_schema = current_schema() and c.column_name = 'updated_at'
  loop
    execute format('create trigger %I before update on %I for each row execute function set_updated_at()',
                   t || '_set_updated_at', t);
  end loop;
end
$$;
