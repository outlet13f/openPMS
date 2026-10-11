-- 내부 코멘트(프로젝트 설정 · 작업 설정): 켜진 프로젝트에서 권한 있는 역할만 쓰고 보는 코멘트. 저장은 journals.is_internal(V1).
alter table projects add column internal_comments boolean not null default false;
