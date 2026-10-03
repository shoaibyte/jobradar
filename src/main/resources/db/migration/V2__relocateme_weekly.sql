-- Full archive of The Global Move's weekly job-list issues (relocateme.substack.com),
-- every section kept, one row per entry per issue. Independent of job_record, which
-- holds only the deduped, section-filtered feed the match engine sees.
create table relocateme_issue (
  id integer primary key autoincrement,
  slug text not null unique,
  week_number integer,                       -- parsed from "...: Week N"; null if absent
  title text not null,
  subtitle text,
  post_date text not null,
  audience text,                             -- only_paid | everyone | founding
  body_status text not null,                 -- FULL | PREVIEW | EMPTY
  advertised_counts text,                    -- JSON: {section: roles} from the issue intro
  job_count integer not null default 0,
  scraped_at text not null
);

create table relocateme_job (
  id integer primary key autoincrement,
  issue_id integer not null references relocateme_issue(id),
  section text not null,                     -- heading the entry sits under, verbatim
  position integer not null,                 -- 1-based within the section
  title text not null,
  company text,
  location text, city text, country text,
  industry_size text,
  keywords text,                             -- JSON array
  apply_url text not null,
  canonical_url text not null,               -- tracking params stripped; join key across weeks
  visa_mentioned integer not null default 0,
  details text,                              -- detail bullets, newline-joined
  unique(issue_id, section, position)
);
create index idx_relocateme_job_canonical on relocateme_job(canonical_url);
create index idx_relocateme_job_issue on relocateme_job(issue_id);
