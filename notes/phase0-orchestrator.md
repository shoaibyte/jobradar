# Phase 0 build notes (orchestrator)

Discoveries made while scaffolding that every Phase 1 subagent should know about.

## Stack version reality (2026-08-05)
- Spring Initializr no longer offers Spring Boot 3.5.x for new projects; the current
  stable line is **4.0.x**. Built against **Spring Boot 4.0.7**.
- Boot 4 renamed/split several starters:
  - `spring-boot-starter-web` -> `spring-boot-starter-webmvc`
  - `spring-boot-starter-test` -> split into per-module test starters
    (`spring-boot-starter-webmvc-test`, `-data-jpa-test`, `-validation-test`,
    `-actuator-test`, `-mail-test`), all already declared in `pom.xml`. JUnit 5,
    Mockito, AssertJ, Spring Test etc. are all still pulled in transitively as before.
  - Flyway autoconfiguration moved out of `spring-boot-autoconfigure` into its own
    module. Plain `org.flywaydb:flyway-core` is **not** enough to get
    `FlywayAutoConfiguration` — use `spring-boot-starter-flyway` (already in `pom.xml`).
- None of this changes how you write code against `core/**` — just noting it so nobody
  is confused if they inspect `pom.xml` and expect `spring-boot-starter-web`/`-test`.

## Persistence: entity ID types (core/persistence, frozen)
SQLite's declared `integer` columns are reported by the JDBC driver as `Types.INTEGER`,
not `Types.BIGINT`. Hibernate's default mapping for a Java `Long` field is `BIGINT`,
which fails `ddl-auto: validate` against the V1 schema's `id integer primary key
autoincrement` columns. Fix applied: **all PK/FK id fields
(`JobRecordEntity.id`, `IngestRunEntity.id`, `MatchResultEntity.jobId`) are typed
`Integer`, not `Long`.** Repository generics are `JpaRepository<X, Integer>`
accordingly. Code against these types as given in `core/persistence/**` — don't
widen back to `Long`, it will break schema validation at boot.

Similarly, `visa_flag` and `notified` (`integer` columns backing Java `boolean`)
needed `@JdbcTypeCode(SqlTypes.INTEGER)` to stop Hibernate expecting a `BOOLEAN`
column type. Already applied in the frozen entities — nothing for you to do here,
just don't be surprised by the annotation.

## Registry types added to core (not in the original spec code blocks)
The spec's `FetchContext` record references a `CompanyRegistry` type that isn't
defined anywhere in the spec text. Added to `core/**` as part of freezing contracts:
`CompanyRegistry` (interface), `CompanyEntry` (record: name, ats, token, url,
relocation, priority), `RelocationPolicy` (COMPANY_WIDE|NONE), `CompanyPriority`
(HIGH|NORMAL). `sources-api` owns the YAML-loading implementation (Section 6);
everyone else only depends on the `core.CompanyRegistry` interface.

Also added to `core/**`: `JobStatus` (ACTIVE|REMOVED), `MatchStrength`
(STRONG|MATCH|PARTIAL), `Fingerprint` (the `sha256(lower(company)|normalizedTitle|
countryIso)` dedupe helper from Section 3, with title normalization for seniority
prefixes and gender-marker suffixes already implemented).

**`MatchEngine` + `MatchOutcome`** were also added to `core/**`. The spec's Section 12
table splits gates/scoring into `match` and lifecycle/notify into `pipeline-notify`,
but those two agents run in parallel and can't see each other's code — without a
frozen interface between them there's no way for pipeline to call match's scoring
engine. `match` provides exactly one Spring bean implementing `core.MatchEngine`
(class name is match's choice); `pipeline-notify` autowires `MatchEngine` by type,
never a concrete class. `evaluate()` returns `Optional.empty()` when a posting is
gated out (fails role/tech/visa gates), a present `MatchOutcome` otherwise.

Same reasoning produced **`core.IngestRunner`** (one method, `Integer runOnce()`):
`web-dashboard`'s `POST /api/run` needs to trigger the exact ingest pass
`pipeline-notify` runs hourly, without depending on pipeline's internal class names.
`pipeline-notify` provides the single `IngestRunner` bean; `web-dashboard` autowires it.

## Detail-page politeness (Section 5 shared rules) vs. the frozen `FetchContext`
Section 5 requires HTML (and Greenhouse/Personio) adapters to skip re-fetching detail
pages for externalIds already known, but `FetchContext` is specified verbatim in the
spec as exactly `(HttpFetcher http, CompanyRegistry registry, Clock clock)` — it
carries no "known IDs" set, and it must not be reshaped. Resolution: adapters that
need this optimization should **constructor-inject `core.persistence.JobRecordRepository`
directly** (a normal Spring bean, not a FetchContext field) and check
`findBySourceAndExternalId(source, externalId).isPresent()` before fetching a detail
page. This is a plain Spring DI dependency, not a file-ownership violation — nothing
about persistence is being edited, just consumed like any other autowired bean.

General pattern across all three added interfaces (`MatchEngine`, `IngestRunner`,
`CompanyRegistry`): exactly one Spring bean per interface, autowired by type across
package boundaries — component scanning covers all of `dev.shoaib.jobradar.**` from
the `@SpringBootApplication` root, so this works with no extra wiring.

`pipeline-notify` should collect all source adapters the same way: autowire
`List<JobSourceAdapter>` (or `ObjectProvider<List<JobSourceAdapter>>`) — Spring
gathers every bean implementing that interface from every package automatically,
so `sources-html`/`sources-api`/`sources-ats` adapters need no manual registration.

## Source-specific fixture/build discoveries
- **japan-dev.com now runs on Nuxt, not Next.js.** The embedded JSON island is
  `<script id="__NUXT_DATA__" type="application/json" data-nuxt-data="nuxt-app">`,
  not `__NEXT_DATA__`. `sources-html` should parse `__NUXT_DATA__` first, falling back
  to DOM scraping per the spec's own fallback clause (Section 5.2). Fixtures in
  `fixtures/japandev/*.html` reflect this (checked: no `__NEXT_DATA__` script present).
- **Vinted's Greenhouse board token could not be discovered.** `careers.vinted.com` is
  confirmed Greenhouse-backed (job IDs visible via `gh_jid=` params and
  `s101.recruiting.eu.greenhouse.io` AI-opt-out links), but
  `boards-api.greenhouse.io/v1/boards/{vinted,vintedgroup,vinted-group,vintedeu,vintedus}`
  all 404, and no board/api token is exposed anywhere in the rendered pages (apply flow
  appears server-proxied). The Vinted entry in `config/companies.yml` is commented out
  with this explanation. No Vinted fixture exists; skip it in tests.
- **Personio feeds for `verimi` and `journi-gmbh` are genuinely empty right now**
  (both companies' careers pages say "no open positions" as of 2026-08-05). The real
  captured empty responses are saved as `fixtures/personio/{verimi,journi-gmbh}.empty.xml`.
  Since an empty feed doesn't exercise field-extraction, `fixtures/personio/{verimi,
  journi-gmbh}.xml` are **hand-built synthetic fixtures** using the real Personio XML
  schema (documented in the file headers) — use these for parser tests, and feel free to
  add a defensive test against the `.empty.xml` variants to confirm an empty feed
  produces an empty `List<JobPosting>` without error.
- **No live company is wired to the generic Lever client** (per spec, Section 4.5).
  `fixtures/lever/sample-postings.json` is a hand-built fixture using Lever's
  documented public JSON schema (`id`, `text`, `categories.{team,location,commitment}`,
  `description`/`descriptionPlain`, `lists[]`, `hostedUrl`, `createdAt`) with one
  matching Java/Go/relocation posting and one clearly non-matching Design posting.
- All 4 Relocate.me seed detail pages from Section 11 resolved live with no churn:
  `paypay/backend-engineer-10205`, `paypay-card/backend-engineer-10180`,
  `picnic/senior-software-engineer-logistics-10261`,
  `hennge/senior-software-engineer-backend-infrastructure-10241`. Fixtures captured
  verbatim in `fixtures/relocateme/`.

## Phase 2 integration fixes (orchestrator)
After all 5 subagents finished, `./mvnw verify` needed the following fixes (all applied
by the orchestrator directly, per Section 12 Phase 2 step 2):
1. **Spring Boot 4 also split RestClient autoconfiguration out of `spring-boot-starter-webmvc`**
   into its own `spring-boot-starter-restclient` starter -- added to `pom.xml`. Without it,
   any bean depending on `RestClient.Builder` (the Slack/Telegram notify channels) failed to
   wire.
2. **Constructor-bound record `@ConfigurationProperties` classes need `@ConfigurationPropertiesScan`.**
   `sources-html`'s `JapanDevProperties`/`RelocateMeProperties` are records annotated
   `@Component @ConfigurationProperties`, which does NOT work for constructor/value-object
   binding via plain component scanning (Spring tried to autowire the record's primitive/String
   constructor params as beans and failed) -- only setter-based JavaBean-style
   `@ConfigurationProperties` (like `match`'s `MatchProperties`) works that way. Fixed by adding
   `@ConfigurationPropertiesScan` to `JobRadarApplication` (now editable in Phase 2) and removing
   the now-redundant/incorrect `@Component` from both record classes.
3. **Spring Boot 4's own Jackson autoconfiguration now produces a Jackson 3
   `tools.jackson.databind.json.JsonMapper` bean, not a classic Jackson 2
   `com.fasterxml.jackson.databind.ObjectMapper`.** `web.JsonCodec` was the only file that
   depended on DI for `ObjectMapper` (every other adapter across the codebase already
   instantiates its own `new ObjectMapper()`, unaffected) -- fixed by doing the same there.
4. **`ThrottledHttpFetcher`'s robots.txt user-agent token must be lower-case**
   (`crawlercommons.robots.SimpleRobotRulesParser` throws otherwise) -- changed `ROBOT_NAMES`
   from `"JobRadar"` to `"jobradar"`.
5. **`ThrottledHttpFetcher.fetchRobots` dropped the port** when building the robots.txt URL
   (`scheme://host/robots.txt`, no `:port`) -- harmless against real production hostnames
   (which rarely use explicit ports) but meant robots.txt was silently unreachable (and
   therefore treated as allow-all) against anything on a non-default port, e.g. every
   WireMock-backed test. Fixed to include the port in both the fetch URL and the robots cache key.
6. **Three `sources-ats` WireMock tests used the static `WireMock.stubFor(...)`/`givenThat(...)`
   helpers against a server started on a random `dynamicPort()`**, but those static helpers talk
   to `localhost:8080` unless `WireMock.configureFor(host, port)` is called first -- added that
   call to each affected test's `@BeforeEach`.
7. **`IngestStartupRunner` ran a real, live-network ingest pass on every `@SpringBootTest`
   context load** (including `JobRadarApplicationTests`), which is slow and impolite to hit live
   external sites on every test run. Added `app.startup-run.enabled` (default `true`) and set it
   `false` via `@SpringBootTest(properties = ...)` on `JobRadarApplicationTests` specifically.
8. **HENNGE's frozen `base-url` (`hennge.com/global/recruit/`) 404s live** (see `sources-html.md`
   finding) -- the real careers site is `recruit.hennge.com`; more specifically the mid-career
   engineering page spec Section 5.5 describes lives at
   `https://recruit.hennge.com/en/mid-career-ngh/`. Updated `app.sources.hennge.base-url` and
   saved that page as `fixtures/hennge/mid-career-ngh.html` for reference. It is *not* a
   structured job-card listing (no `article`/`.job-list__item`-style markup -- it reads as a
   single long-form page about the hiring track), so `HenngeAdapter`'s generic card-detection
   still degrades to an empty list against it. Left as-is: HENNGE is an explicitly
   lower-priority, PARTIAL-match-only source per the spec, and its one real, fully-parsed
   posting is already captured via Relocate.me and exercised by `RelocateMeAdapterTest`.

After these fixes: `./mvnw -q verify` is green, 116/116 tests pass, 0 failures/errors, full
build completes in ~11s (previously >60s due to the live-network startup-run issue above).

## Frozen as of this commit
`pom.xml`, `application.yml`, `core/**` (incl. `core/persistence/**`),
`db/migration/V1__init.sql`, `config/companies.yml`, all of
`src/test/resources/fixtures/**`. `./mvnw -q compile` passes.
