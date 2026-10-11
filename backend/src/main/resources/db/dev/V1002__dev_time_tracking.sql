-- 개발 데이터: 시간 기록 모듈과 지난주 공수 몇 건(V17 뒤, V1000 프로젝트에)
update projects set enabled_modules = array_append(enabled_modules, 'time_tracking')
  where identifier in ('web-platform', 'mobile-app', 'infra', 'infra-backup') and not ('time_tracking' = any (enabled_modules));

insert into time_entries (user_id, project_id, work_package_id, activity_id, spent_on, hours, comment, created_at, updated_at)
select u.id, w.project_id, w.id, a.id, e.spent_on::date, e.hours, e.comment, '2026-10-09T09:00:00Z', '2026-10-09T09:00:00Z'
from (values
  ('seoyeon.kim@openpms.local', 1, '설계', '2026-10-05', 3.0, '릴리스 범위 정리'),
  ('seoyeon.kim@openpms.local', 3, '회의', '2026-10-05', 1.5, '화면 설계 검토 회의'),
  ('seoyeon.kim@openpms.local', 3, '설계', '2026-10-06', 4.0, ''),
  ('seoyeon.kim@openpms.local', 12, '개발', '2026-10-07', 6.0, '요약 메일 초안'),
  ('seoyeon.kim@openpms.local', 1, '관리', '2026-10-08', 2.0, ''),
  ('haneul.lee@openpms.local', 9, '개발', '2026-10-05', 7.0, '키보드 이동 구현'),
  ('haneul.lee@openpms.local', 9, '테스트', '2026-10-06', 3.5, ''),
  ('haneul.lee@openpms.local', 4, '개발', '2026-10-06', 4.5, '결제 API 연동'),
  ('junho.park@openpms.local', 2, '문서', '2026-10-05', 2.0, '요구사항 정리'),
  ('junho.park@openpms.local', 4, '개발', '2026-10-07', 8.0, ''),
  ('junho.park@openpms.local', 13, '개발', '2026-10-08', 5.0, '배포 스크립트')
) as e (email, number, activity, spent_on, hours, comment)
join users u on u.email = e.email
join work_packages w on w.number = e.number
join time_activities a on a.name = e.activity;
