-- 기한 지남 알림은 기본으로 끈다(시작·완료 1일 전 알림만 기본으로 켬). 아직 아무도 설정을 저장하지 않았으므로
-- 기존 행은 기본값을 따르게 고친다: 사용자가 이 값을 바꾸는 화면(S20)은 이 마이그레이션과 함께 생겼다.
alter table notification_settings alter column overdue_alert set default false;
update notification_settings set overdue_alert = false where overdue_alert;
