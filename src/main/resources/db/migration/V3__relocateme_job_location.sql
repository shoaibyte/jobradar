-- UI-facing location fields for relocateme_job, split out of the free-text location.
alter table relocateme_job add column remote integer not null default 0;
alter table relocateme_job add column remote_region text;     -- "EMEA, LATAM"; null = not stated
alter table relocateme_job add column country_codes text;     -- JSON array of ISO alpha-2, e.g. ["SG","US"]
alter table relocateme_job add column company_linkedin_url text;
create index idx_relocateme_job_remote on relocateme_job(remote);
