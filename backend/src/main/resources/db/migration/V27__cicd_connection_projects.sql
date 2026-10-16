-- CI/CD 연동마다 허용 프로젝트(REQ-CICD-010): 저장소를 찾고 붙일 수 있는 프로젝트를 시스템 관리자가 정한다.
-- 목록에 없는 프로젝트에는 연동이 없는 것처럼 보인다(같은 404). 새 연동은 빈 목록으로 시작한다.

create table cicd_connection_projects (
  connection_id uuid not null references cicd_connections (id) on delete cascade,
  project_id    uuid not null references projects (id) on delete cascade,
  primary key (connection_id, project_id)
);
create index cicd_connection_projects_project on cicd_connection_projects (project_id);

-- 이미 저장소가 붙은 프로젝트는 그대로 쓸 수 있게 허용 목록에 넣는다
insert into cicd_connection_projects (connection_id, project_id)
  select distinct connection_id, project_id from cicd_repositories
  on conflict do nothing;
