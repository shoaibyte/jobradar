create table job_record (
  id integer primary key autoincrement,
  source text not null, external_id text not null, fingerprint text not null,
  title text not null, company text, city text, country text,
  url text not null, description text,
  tech_tags text, benefits text,           -- JSON arrays as text
  salary_raw text, visa_flag integer not null default 0,
  posted_at text, first_seen text not null, last_seen text not null,
  status text not null default 'ACTIVE',   -- ACTIVE | REMOVED
  unique(source, external_id)
);
create index idx_job_fingerprint on job_record(fingerprint);

create table match_result (
  job_id integer primary key references job_record(id),
  score real not null, strength text not null,          -- STRONG | MATCH | PARTIAL
  reasons text, evaluated_at text not null,
  notified integer not null default 0
);

create table ingest_run (
  id integer primary key autoincrement,
  started_at text not null, finished_at text,
  stats text                                -- JSON: per-source {fetched,new,updated,removed,errors}
);
