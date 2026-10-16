-- 개발 데이터: 개발용 GitLab 연동의 허용 프로젝트(V27 뒤). 새 개발 DB에서는 V27이 V1010보다 먼저 돌아 채울 저장소가 없으므로
-- V1010의 저장소가 붙은 web-platform과 mobile-app을 여기서 넣는다(이미 있으면 그대로, 연동이나 프로젝트가 없는 DB에서는 아무것도 안 함).

insert into cicd_connection_projects (connection_id, project_id)
select c.id, p.id
  from cicd_connections c
  join projects p on p.id in ('935daeb0-d1aa-57f4-88e1-0050b30413ac', '09e67323-1a1d-5ccd-854b-78e0628e38c0')
 where c.id = 'fab25892-ec02-5f18-85b0-eebc81857f6b'
on conflict do nothing;
