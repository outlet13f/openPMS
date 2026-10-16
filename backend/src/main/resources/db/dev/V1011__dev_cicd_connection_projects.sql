-- 개발 데이터: 개발용 GitLab 연동의 허용 프로젝트(V27 뒤). 새 개발 DB에서는 V27이 V1010보다 먼저 돌아 채울 저장소가 없으므로
-- V1010의 저장소가 붙은 web-platform과 mobile-app을 여기서 넣는다(이미 있으면 그대로).

insert into cicd_connection_projects (connection_id, project_id) values
  ('fab25892-ec02-5f18-85b0-eebc81857f6b', '935daeb0-d1aa-57f4-88e1-0050b30413ac'),
  ('fab25892-ec02-5f18-85b0-eebc81857f6b', '09e67323-1a1d-5ccd-854b-78e0628e38c0')
on conflict do nothing;
