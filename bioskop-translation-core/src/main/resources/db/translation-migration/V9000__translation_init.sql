create table enum_output_type (
    id smallint primary key,
    code varchar(32) not null unique,
    description text
);

create table enum_translation_status (
    id smallint primary key,
    code varchar(32) not null unique,
    description text
);

create table enum_context_file_type (
    id smallint primary key,
    code varchar(32) not null unique,
    description text
);

insert into enum_output_type (id, code, description) values
    (1, 'QUICK', 'Quick close-to-original translation'),
    (2, 'ADAPTED', 'Adapted context-aware translation');

insert into enum_translation_status (id, code, description) values
    (1, 'PENDING', 'Waiting for processing'),
    (2, 'IN_PROGRESS', 'Processing is in progress'),
    (3, 'COMPLETED', 'Processing completed successfully'),
    (4, 'FAILED', 'Processing failed');

insert into enum_context_file_type (id, code, description) values
    (1, 'NOTES', 'Free-form translation notes'),
    (2, 'CHARACTERS', 'Structured character metadata'),
    (3, 'SPEAKERS', 'Cue-to-speaker mapping'),
    (4, 'OTHER', 'Other context file');

create table translation_source (
    id uuid primary key,
    lang varchar(16) not null,
    uri text not null,
    checksum varchar(128),
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table translation_context_file (
    id uuid primary key,
    source_id uuid not null references translation_source(id),
    file_type smallint not null references enum_context_file_type(id),
    uri text not null,
    checksum varchar(128),
    created_at timestamptz not null
);

create table translation_output (
    id uuid primary key,
    source_id uuid not null references translation_source(id),
    source_lang varchar(16) not null,
    target_lang varchar(16) not null,
    output_type smallint not null references enum_output_type(id),
    status smallint not null references enum_translation_status(id),
    uri text,
    checksum varchar(128),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    unique (source_id, target_lang, output_type)
);

create table translation_attempt (
    id uuid primary key,
    output_id uuid not null references translation_output(id),
    attempt_no int not null,
    status smallint not null references enum_translation_status(id),
    started_at timestamptz not null,
    finished_at timestamptz,
    error_code varchar(128),
    error_message text,
    unique (output_id, attempt_no)
);
