-- 고대비 테마(계정 설정 · 화면): 라이트 고대비. 테마 값 목록만 넓힌다.
alter table users drop constraint users_theme_check;
alter table users add constraint users_theme_check check (theme in ('system', 'light', 'dark', 'high_contrast'));
