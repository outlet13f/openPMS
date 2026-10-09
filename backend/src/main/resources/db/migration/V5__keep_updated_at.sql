-- 관리 작업(사용자 정의 필드 삭제, 선택지 정리)은 작업의 '최근 수정' 시각을 바꾸지 않는다.
-- 트랜잭션에서 `set local openpms.keep_updated_at = 'on'`을 하면 그 트랜잭션의 갱신은 updated_at을 그대로 둔다.
create or replace function set_updated_at() returns trigger
language plpgsql as $$
begin
  if coalesce(current_setting('openpms.keep_updated_at', true), '') = 'on' then
    return new;
  end if;
  new.updated_at := now();
  return new;
end
$$;
