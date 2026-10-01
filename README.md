# JobRadar

A personal, self-hosted job aggregator. It pulls backend engineering postings from
several job boards and ATS platforms, keeps only roles that carry a visa/relocation
signal and fit a Java-primary / Go-secondary profile, tracks postings hourly (new /
still-active / removed), and notifies you about new matches.

## Stack

- Java 21, Spring Boot 4.0.7 (Maven, wrapper included — `./mvnw`)
- SQLite (`./data/jobradar.db`) via `hibernate-community-dialects` + Flyway
- Virtual threads for concurrent source fetching (`spring.threads.virtual.enabled=true`)
- jsoup for HTML scraping, `crawler-commons` for robots.txt, `spring-retry` for HTTP retries
- A one-file vanilla-JS dashboard (`src/main/resources/static/index.html`), no build step

**A note on versions**: the spec asked for "the current 3.5.x/4.x line" — by the time
this was built, Spring Initializr no longer offered 3.5.x for new projects, so this runs
on **Spring Boot 4.0.7**. That version renamed/split several starters compared to 3.x:
`spring-boot-starter-web` → `spring-boot-starter-webmvc`; `spring-boot-starter-test` →
one test starter per module; `RestClient` autoconfiguration moved out of the web starter
into `spring-boot-starter-restclient`; Flyway autoconfiguration moved into
`spring-boot-starter-flyway` (plain `flyway-core` isn't enough on its own); and Jackson's
own autoconfiguration now produces a Jackson 3 `JsonMapper` bean rather than a classic
Jackson 2 `ObjectMapper` (code that needs an `ObjectMapper` just instantiates its own —
see [Architecture decisions](#architecture-decisions)). None of this affects how the app
behaves; it's just what to expect if you go looking for `spring-boot-starter-web` in
`pom.xml` and don't find it.

## Setup

```bash
./mvnw clean verify   # compiles, runs all 116 tests (fixture-backed, no live network)
```

The database and export directory are created automatically on first run (`data/`,
`export/` — both gitignored). No manual schema setup needed; Flyway runs
`db/migration/V1__init.sql` on startup.

### Environment variables

All optional. Missing config means that feature silently does nothing (no crash):

| Variable | Purpose |
|---|---|
| `JOBRADAR_CONTACT_EMAIL` | Put in the `User-Agent` header sent to every source (be a good citizen) |
| `SUBSTACK_COOKIE` | Session cookie of a paid [The Global Move](https://relocateme.substack.com) subscriber — unlocks the full weekly job lists for the `relocateme-substack` source (see below) |
| `SLACK_WEBHOOK_URL` | Enables the Slack notification channel |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | Enables the Telegram notification channel |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` | Mail server for the email channel (also needs `app.notify.email.enabled=true` and `JOBRADAR_NOTIFY_EMAIL`, both off by default) |
| `SERVER_PORT` | HTTP port for the dashboard/API (default 8080) |

### Unlocking the paid Substack weekly job lists

The `relocateme-substack` source ingests The Global Move's weekly hand-curated job
issues (~30 backend roles/week). Those issues are paid-subscriber-only; anonymously the
adapter can only report how many roles each issue holds ("advertises 33 back end, 12
full stack role(s) behind the paywall"). If you subscribe ($15/mo), export your own
session cookie once:

1. Log in to `relocateme.substack.com` in your browser.
2. DevTools → Application/Storage → Cookies → `https://relocateme.substack.com`.
3. Copy the `substack.sid` value and set `SUBSTACK_COOKIE="substack.sid=<value>"`.

The cookie is sent only to `relocateme.substack.com` (`app.http.auth.cookies` maps
cookies per host). The adapter self-verifies every issue against the per-section totals
the issue advertises about itself, so the logs tell you exactly which state you're in:

| Log | Meaning |
|---|---|
| `... all advertised section totals met` | Full body parsed; every advertised role extracted |
| `WARN ... parsed fewer entries than the issue advertises` | Markup drift or truncated body — parser needs a look |
| `WARN ... cookie has likely expired or is malformed` | Cookie was sent but Substack served the preview — re-export it |
| `INFO ... advertises N ... behind the paywall` | No cookie configured; inventory only |

## Run modes

```bash
./mvnw spring-boot:run                                          # normal: run once now, then hourly forever
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.run-once=true   # one pass, then exit
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.dry-run=true    # fetch + match + log, no writes/notifications
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.detect-ats=https://careers.example.com/
```

Or against the packaged jar (`./mvnw package` first):

```bash
java -jar target/jobradar-0.0.1-SNAPSHOT.jar --app.run-once=true
```

`--app.run-once` and `--app.dry-run` combine freely. `--app.detect-ats=<url>` is a
one-shot utility command — it fetches the URL, probes for ATS markers, prints a
ready-to-paste `companies.yml` entry (or `unsupported ATS: <name>`), and exits without
touching the scheduler or the DB.

### Hourly schedule

The app runs one ingest pass immediately on startup, then on `app.schedule.cron`
(default `0 7 * * * *` — seven minutes past every hour, off the top-of-hour traffic
spike). If you'd rather not keep a JVM running continuously, run it as a one-shot via
cron/systemd instead and disable the in-process scheduler by always passing
`--app.run-once=true`:

```cron
# crontab -e
7 * * * * cd /path/to/jobradar && java -jar target/jobradar-0.0.1-SNAPSHOT.jar --app.run-once=true >> /var/log/jobradar.log 2>&1
```

```ini
# /etc/systemd/system/jobradar.service
[Unit]
Description=JobRadar ingest pass

[Service]
Type=oneshot
WorkingDirectory=/path/to/jobradar
ExecStart=/usr/bin/java -jar target/jobradar-0.0.1-SNAPSHOT.jar --app.run-once=true

# /etc/systemd/system/jobradar.timer
[Unit]
Description=Run JobRadar hourly

[Timer]
OnCalendar=*-*-* *:07:00
Persistent=true

[Install]
WantedBy=timers.target
```

### Adding a company

1. Find its careers page and run `--app.detect-ats=<url>` (see above).
2. If it prints a Greenhouse/Personio/Lever snippet, paste it into `config/companies.yml`
   and restart — a matching-ATS adapter bean is created automatically for every row.
3. If it prints `unsupported ATS: <name>` (SmartRecruiters/Ashby/Recruitee/Workable) or
   nothing recognizable, it needs a dedicated HTML adapter under `sources/html/**`
   instead (see `HenngeAdapter`/`PicnicAdapter` for the pattern).

## API

| Endpoint | Purpose |
|---|---|
| `GET /api/matches?strength=&minScore=&status=` | Filtered, score-sorted match list |
| `GET /api/jobs/{id}` | One job record, full detail |
| `GET /api/runs?limit=` | Recent ingest run history + per-source stats |
| `GET /api/companies` | Registry + live ACTIVE-posting counts per company |
| `POST /api/run` | Trigger an ingest pass now (blocking; skips if one's already running) |
| `GET /actuator/health` | Liveness |

Dashboard: `http://localhost:8080/` (or whatever `SERVER_PORT` you set).

## Architecture decisions

**Adapter pattern** (`core.JobSourceAdapter`): every source — HTML-scraped
(Relocate.me, Picnic), API-based (Arbeitnow, HN "Who is
hiring?"), or ATS-driven (Greenhouse/Personio/Lever, one bean per `companies.yml` row) —
implements the same `fetch(FetchContext) -> List<JobPosting>` contract. The ingest
orchestrator (`pipeline.IngestOrchestrator`) never knows which kind of source it's
talking to; it just autowires `List<JobSourceAdapter>` and Spring hands it every bean
that implements the interface, from every package, with zero manual registration.
Company-specific ATS adapters (`sources/ats/AtsAdapterRegistrar`) are registered
dynamically at bean-definition time from `config/companies.yml`, so adding a company is
a one-line YAML change, not a code change.

**Snapshot-diff lifecycle**: each adapter returns its *entire current* listing on every
run, not a delta. The pipeline diffs that snapshot against what's in `job_record`:
unseen `(source, externalId)` → `NEW`; seen and still present → refresh `lastSeen`;
seen previously but *absent* from a successful fetch → `REMOVED`. Removal only happens
after a fetch that didn't throw, and never for sources that opt out
(`supportsRemovalDetection() == false`, i.e. HN) — a transient network failure or a
source that structurally can't reliably report "still there" must never look like a
mass delisting.

**Politeness by design, not by convention**: `HttpFetcher` (the one interface every
adapter goes through for actual HTTP) enforces a minimum interval per host, honors
robots.txt (cached, per host), retries 5xx/timeouts with backoff, and sends an
identifying `User-Agent`. Combined with the "only fetch a detail page for externalIds
you haven't seen before" rule (listing-page presence is enough to confirm a known job
is still active), the hourly footprint against each site stays small regardless of how
many total jobs are being tracked.

**Cross-cutting Spring wiring for a parallel-agent build**: this codebase was built by
five agents working concurrently in separate packages, each blind to the others' code.
Three small interfaces in `core/**` exist purely to make that possible without any of
them touching a shared file: `MatchEngine` (so `pipeline` can call `match`'s scoring
without knowing its class name), `IngestRunner` (so `web` can trigger the same ingest
pass the scheduler runs), and `CompanyRegistry` (so `match`, `pipeline`, and `web` can
all read `companies.yml` through one shared implementation). Each has exactly one Spring
bean; every consumer autowires the interface. The full build log — including two real
Spring Boot 4 gotchas discovered along the way (constructor-bound `@ConfigurationProperties`
records need `@ConfigurationPropertiesScan`; the modularized RestClient/Flyway/Jackson
autoconfiguration) — is written up in `notes/*.md`.

## Known limitations

- **Vinted's Greenhouse board token couldn't be found.** `careers.vinted.com` is
  confirmed Greenhouse-backed, but the public Job Board API token isn't discoverable by
  any means tried (see `config/companies.yml` and `notes/phase0-orchestrator.md`). Its
  entry is commented out; re-enable it if you find the real token.
- **No cross-run mutual exclusion beyond a simple lock**: `POST /api/run` firing at the
  same moment as the hourly scheduled run will have the second caller skip (logged), not
  queue — fine for personal use, would need real job-queue semantics for anything busier.

## Final report

- `./mvnw verify`: **green**, 116/116 tests pass, 0 failures/errors (~11s once the
  test suite stopped hitting live network — see `notes/phase0-orchestrator.md` item 7).
- All three run modes (`--app.run-once`, `--app.dry-run`, `--app.detect-ats`) exercised
  live against real sources during Phase 2 verification, all exit cleanly (0).
- First real `--app.run-once`, all 12 adapters, **zero errors** on any source:

  | Source | Fetched |
  |---|---|
  | arbeitnow | 323 |
  | tokyodev | 148 |
  | greenhouse:paypay | 86 |
  | japandev | 55 |
  | greenhouse:paypaycard | 39 |
  | relocateme | 38 |
  | greenhouse:paypaysec | 20 |
  | custom-html:picnic | 11 |
  | hn-who-is-hiring | 4 |
  | custom-html:hennge | 3 |
  | personio:verimi | 0 (feed genuinely empty right now) |
  | personio:journi-gmbh | 0 (feed genuinely empty right now) |

  **727 job postings ingested**, all `ACTIVE`; **92 passed every gate** — 19 STRONG, 46
  MATCH, 27 PARTIAL. Top STRONG matches confirm the spec's own Section 11 live checks:
  PayPay and PayPay Card "Backend Engineer" postings score STRONG (7–12), several Picnic
  Software Engineer roles score STRONG (9), and a Money Forward "Principal Engineer"
  posting still clears STRONG despite the seniority down-rank — confirming down-rank
  (not exclude) is working as specified. Full detail in `export/matches.json`/`.csv`
  (gitignored, regenerated every run) and queryable directly:
  `sqlite3 data/jobradar.db "select j.title, j.company, m.strength, m.score from job_record j join match_result m on m.job_id=j.id order by m.score desc"`.
- **Discovered live**: Vinted's Greenhouse token (documented, disabled) and the correct
  HENNGE domain (fixed in `application.yml`) — see Known limitations above.
- **Deferred**: no company was added to the optional/commented Zalando-style watchlist
  entries in `config/companies.yml` — `--app.detect-ats` works (verified live against
  `careers.vinted.com`, correctly identifying Greenhouse markers) but no additional
  token was manually confirmed beyond what the spec's seed list already covers.
