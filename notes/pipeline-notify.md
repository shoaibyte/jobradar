# pipeline-notify implementation notes

## Scope delivered
- `core.IngestRunner` bean: `pipeline.IngestOrchestrator` (also hosts the `@Scheduled`
  hourly run method).
- `core.HttpFetcher` bean: `pipeline.ThrottledHttpFetcher` (see below).
- Startup/run-mode handling: `pipeline.IngestStartupRunner` (`ApplicationRunner`).
- `@EnableScheduling` / `@EnableRetry`: `pipeline.PipelineConfig`.
- Notifications: `notify.NotificationChannel` + Console/Slack/Telegram/Email
  implementations, `notify.NotificationFormatter` (shared batching/formatting),
  `notify.NotifyConfig` (the one shared `RestClient` bean for Slack/Telegram).

## HttpFetcher bean location decision
No other agent's package had an `HttpFetcher` implementation at the time this was
written (checked via `grep -rl "implements HttpFetcher"` across `src/`), and per
the orchestrator's phase0 notes this interface isn't explicitly assigned to
anyone. Implemented it in `pipeline/ThrottledHttpFetcher.java` since ingest
orchestration is what most needs a working fetcher for the app to run
end-to-end. It is a normal `@Component` implementing the frozen `core.HttpFetcher`
interface -- the interface file itself was never touched.

Design:
- **RestClient** built with a `JdkClientHttpRequestFactory` wrapping a
  `java.net.http.HttpClient` configured with `app.http.connect-timeout-seconds`;
  `setReadTimeout` uses `app.http.read-timeout-seconds`.
- **Per-host throttling**: a `ConcurrentHashMap<String, Object>` of per-host
  monitor locks. The *entire* robots-check + wait + HTTP call for a given host
  runs inside `synchronized(hostLock)`, so two sources hitting the same host are
  fully serialized (not just interval-checked), while different hosts run truly
  in parallel across the virtual-thread fan-out in `IngestOrchestrator`. Enforces
  `app.http.per-host-min-interval-ms` via `Thread.sleep` (safe on virtual threads).
- **Retry**: `@Retryable` on both `get()` and `tryGet()`, retrying on 5xx
  (wrapped in a local unchecked `HttpServerErrorException5xx` so it's usable as a
  `retryFor` target without also matching 4xx) and `ResourceAccessException`
  (timeouts/connect failures), using `app.http.retry.max-attempts` /
  `initial-backoff-ms` / `backoff-multiplier` via SpEL expression attributes. A
  robots.txt disallow throws a separate unchecked `RobotsDisallowedException`
  that is *not* in `retryFor` -- a disallow is not transient.
- **robots.txt**: `crawler-commons` `SimpleRobotRulesParser` / `BaseRobotRules`,
  cached per host with TTL from `app.http.robots-txt.cache-ttl-minutes`, gated by
  `app.http.robots-txt.enabled`. A missing/unfetchable robots.txt is treated as
  allow-all (parses empty content), matching normal crawler convention.
- `get()` throws on 404 (via a local unchecked `HttpFetchException`); `tryGet()`
  returns `Optional.empty()` on 404 per the interface's documented contract.

Known gap: constructed directly (no Spring context) in
`ThrottledHttpFetcherTest`, so `@Retryable`'s AOP proxy is inert in that test --
those tests cover the base fetch/robots/throttle logic against a local WireMock
server, not the retry-on-5xx path itself (there was no cheap way to stand up a
real `@EnableRetry` proxy without a full Spring context in a unit test).

## Lifecycle / dedupe (Section 8)
Implemented in `IngestOrchestrator.runOnce()`, matching the 8 steps in the spec
almost verbatim (see the per-step comments in the source). Two implementation
choices worth flagging:

1. **"Updated" heuristic**: an existing `job_record` is marked changed if its
   `description`, `techTags` (JSON), `benefits` (JSON), or `salaryRaw` differ
   from the newly-fetched posting. This is the "cheap, doesn't need to be
   perfect" heuristic the spec explicitly allows.
2. **REMOVED -> ACTIVE reactivation**: if a posting that was previously marked
   `REMOVED` reappears in a later fetch (matched by `(source, externalId)`),
   the record is flipped back to `ACTIVE` and treated as "changed" (so it goes
   through match re-evaluation and can be re-notified). The spec's lifecycle
   diagram is `NEW -> ACTIVE -> REMOVED`; this adds the (unstated but sensible)
   `REMOVED -> ACTIVE` edge for the "company reposted the same job" case. Flagging
   this as a deliberate addition beyond the literal spec text, not a contract change.

`dryRun=true` runs steps 1/2/3/5 as instructed (fetch + simulate upsert + match,
logging counts) but persists nothing and returns `null` from `runOnce()` since
there is no `ingest_run` row to give an id for. `IngestRunner.runOnce()`'s
Javadoc doesn't say the id is ever non-null, so this is a reasonable reading;
flagging in case `web-dashboard`'s `POST /api/run` assumes a non-null id --
in this codebase that only matters if dry-run is enabled for a live server,
which isn't the normal operating mode.

One more minor edge case: if a `MatchResultEntity` in the unnotified batch
turns out to have no corresponding `job_record` (defensive-only, shouldn't
happen in practice), it's silently dropped from the outgoing notification
content but still flipped to `notified=true` along with the rest of the batch
(to avoid it blocking the unnotified query forever). Not expected to occur in
practice since `job_record` rows are never deleted, only status-flipped.

No locking/mutex exists around concurrent `runOnce()` invocations -- if
`web-dashboard`'s `POST /api/run` fires at the same moment the hourly
`@Scheduled` run does, both would execute concurrently against the same DB.
Not called out in the spec; noting it as a known gap rather than guessing at
an API (e.g. `@Scheduled` + a manual trigger sharing a lock) that might
conflict with how `web-dashboard` expects `IngestRunner` to behave.

## Notifications (Section 10)
`notify.NotificationChannel` is a small internal interface
(`void notify(List<MatchNotification> newMatches)`); `MatchNotification` is a
notify-package-local record so `notify` never has to depend on
`core.persistence` entities. `IngestOrchestrator` calls each channel exactly
once per run with the full batch, so "one message per channel per run" falls
out naturally rather than needing extra batching logic in each channel.

- Console: always logs (no gate on `app.notify.console.enabled`, per the spec's
  literal "always on").
- Slack / Telegram: share one `RestClient` bean (`notify.NotifyConfig`) built
  from Spring Boot's auto-configured `RestClient.Builder`. Both take a built
  `RestClient` (not a `Builder`) in their constructors specifically so tests can
  pass a mock `RestClient` directly and assert zero interactions when config is
  blank, without stubbing a builder chain.
- Email: gated on `app.notify.email.enabled` (default false) and a non-blank
  `app.notify.email.to`; uses the auto-configured `JavaMailSender`.
- Java/Go flags in the message body use simple `\bjava\b` / `\bgo(lang)?\b`
  word-boundary regexes over `techTags + description`, per the spec's "simple
  substring check is fine" allowance (word-boundaries used only to avoid
  "going"/"google" false-positiving as Go).

## Testing
All in `src/test/java/dev/shoaib/jobradar/{pipeline,notify}`, no real network
calls (WireMock for `ThrottledHttpFetcher`, mocked `RestClient`/`JavaMailSender`
for notification channels, mocked repositories for orchestration -- no
`@DataJpaTest`/real DB used anywhere):
- `IngestOrchestratorLifecycleTest`: NEW insert, REMOVED on disappearance,
  REMOVED->ACTIVE reactivation, no-mass-removal-on-fetch-exception, and
  `supportsRemovalDetection()==false` is never used for removal.
- `IngestOrchestratorExportTest`: `matches.json`/`matches.csv` contents
  (including CSV quoting of a comma-containing company name) via `@TempDir`.
- `ThrottledHttpFetcherTest`: 404 contract (`get` throws / `tryGet` empty),
  robots.txt disallow blocks a fetch, robots check skips cleanly when disabled,
  and per-host minimum interval is actually enforced.
- `SlackNotificationChannelTest` / `TelegramNotificationChannelTest` /
  `EmailNotificationChannelTest`: no-op (zero interactions) on blank/disabled
  config, exactly one outbound call for a batch of 2+ matches.

## Deviations / things a reviewer should double check
- `HttpFetcher` bean placement (see above) -- if another agent also shipped one,
  the orchestrator will need to pick a winner in Phase 2 per the task brief.
- `REMOVED -> ACTIVE` reactivation on re-upsert (see above).
- `runOnce()` returns `null` in dry-run mode.
- No cross-run mutual exclusion for `runOnce()`.
