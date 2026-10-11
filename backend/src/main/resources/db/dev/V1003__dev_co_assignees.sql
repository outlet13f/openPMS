-- 개발 데이터: 공동 담당자(V18 뒤). 개발 데이터의 작업은 V18보다 늦게 들어오므로 지금 담당자를 첫 담당자로 옮기고,
-- 결제 API 연동(#4)은 박준호(24시간)와 이하늘(16시간)이 함께 맡는다.
insert into work_package_assignees (work_package_id, user_id, position)
select id, assignee_id, 0 from work_packages where assignee_id is not null
on conflict (work_package_id, user_id) do nothing;

update work_package_assignees a set work_hours = 24
from work_packages w join projects p on p.id = w.project_id
where a.work_package_id = w.id and p.identifier = 'web-platform' and w.number = 4 and a.position = 0;

insert into work_package_assignees (work_package_id, user_id, position, work_hours)
select w.id, u.id, 1, 16
from work_packages w join projects p on p.id = w.project_id join users u on u.email = 'haneul.lee@openpms.local'
where p.identifier = 'web-platform' and w.number = 4
on conflict (work_package_id, user_id) do nothing;
