create table enum_translation_status (
    id smallint primary key,
    code varchar(32) not null unique,
    description text
);

insert into enum_translation_status (id, code, description) values
    (1, 'PENDING', 'Waiting for processing'),
    (2, 'IN_PROGRESS', 'Processing is in progress'),
    (3, 'COMPLETED', 'Processing completed successfully'),
    (4, 'FAILED', 'Processing failed');

create table translation_job (
    id uuid primary key,
    source_text_id uuid not null,
    source_path text not null,
    source_lang varchar(16) not null,
    target_lang varchar(16) not null,
    target_path text not null,
    status smallint not null references enum_translation_status(id),
    attempts int not null default 0,
    error_code varchar(128),
    error_message text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    unique (source_text_id, target_lang)
);

create table translation_job_attempt (
    id uuid primary key,
    job_id uuid not null references translation_job(id),
    attempt_no int not null,
    status smallint not null references enum_translation_status(id),
    started_at timestamptz not null,
    finished_at timestamptz,
    error_code varchar(128),
    error_message text,
    unique (job_id, attempt_no)
);

create index idx_translation_job_status_updated on translation_job(status, updated_at);
create index idx_translation_job_target_path on translation_job(target_path);
create index idx_translation_job_attempt_job_id on translation_job_attempt(job_id);

