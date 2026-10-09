-- 예약 작업(architecture.md §4 작업): 날짜 알림, 즉시·요약 메일.

-- 요약 메일을 마지막으로 처리한 시각. 요약 시각이 지났고 이 값이 그 시각보다 이르면 보낸다(하루 1회 보장).
alter table user_preferences add column last_digest_at timestamptz;

-- 날짜 알림 기본값: 시작일·완료일 1일 전, 기한 지남 알림 켬. 설정 행이 없는 사용자도 같은 값을 쓴다(jobs/DateAlerts).
alter table notification_settings
  alter column start_date_alert_days set default 1,
  alter column due_date_alert_days set default 1,
  alter column overdue_alert set default true;
