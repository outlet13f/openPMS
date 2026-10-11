-- 프로젝트 개요(S05) 위젯: 보이는 위젯과 순서. 프로젝트를 고칠 수 있는 사람이 정한다.
alter table projects add column overview_widgets text[] not null default '{description,workSummary,status,members}';
