-- 내 페이지(S21) 위젯: 보여 줄 위젯과 순서. 알려진 이름만 허용(AccountController가 검사)
alter table user_preferences add column my_page_widgets text[] not null default '{assigned,created}';
