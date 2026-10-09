-- Library tables (architecture.md §1): Spring Session JDBC and db-scheduler, in their PostgreSQL forms.

-- Spring Session JDBC (org/springframework/session/jdbc/schema-postgresql.sql)
create table spring_session (
  primary_id            char(36) not null,
  session_id            char(36) not null,
  creation_time         bigint not null,
  last_access_time      bigint not null,
  max_inactive_interval int not null,
  expiry_time           bigint not null,
  principal_name        varchar(100),
  constraint spring_session_pk primary key (primary_id)
);
create unique index spring_session_ix1 on spring_session (session_id);
create index spring_session_ix2 on spring_session (expiry_time);
create index spring_session_ix3 on spring_session (principal_name);

create table spring_session_attributes (
  session_primary_id char(36) not null,
  attribute_name     varchar(200) not null,
  attribute_bytes    bytea not null,
  constraint spring_session_attributes_pk primary key (session_primary_id, attribute_name),
  constraint spring_session_attributes_fk foreign key (session_primary_id)
    references spring_session (primary_id) on delete cascade
);

-- db-scheduler (one table, cluster-safe)
create table scheduled_tasks (
  task_name            text not null,
  task_instance        text not null,
  task_data            bytea,
  execution_time       timestamptz not null,
  picked               boolean not null,
  picked_by            text,
  last_success         timestamptz,
  last_failure         timestamptz,
  consecutive_failures int,
  last_heartbeat       timestamptz,
  version              bigint not null,
  priority             smallint,
  primary key (task_name, task_instance)
);
create index scheduled_tasks_execution_time_idx on scheduled_tasks (execution_time);
create index scheduled_tasks_last_heartbeat_idx on scheduled_tasks (last_heartbeat);
create index scheduled_tasks_priority_execution_time_idx on scheduled_tasks (priority desc, execution_time asc);
