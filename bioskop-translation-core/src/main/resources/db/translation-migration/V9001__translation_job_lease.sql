alter table translation_job
    add column lease_token uuid,
    add column lease_expires_at timestamptz;

create index idx_translation_job_status_lease_expires
    on translation_job(status, lease_expires_at, created_at);
