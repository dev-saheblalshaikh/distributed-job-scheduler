alter table jobs add column origin varchar(16) not null default 'api';
create index idx_jobs_origin_created_at on jobs(origin, created_at);
