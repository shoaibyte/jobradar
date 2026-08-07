# web-dashboard agent notes

## Package layout
- `dev.shoaib.jobradar.web` — one `@RestController` per resource: `MatchController`,
  `JobController`, `RunController`, `CompanyController`, plus a small shared
  `JsonCodec` `@Component` (wraps the auto-configured Jackson `ObjectMapper` to turn the
  JSON-as-text columns — `tech_tags`/`benefits`/`reasons`/`stats` — back into real
  lists/maps for responses).
- `dev.shoaib.jobradar.web.dto` — response records: `MatchView`, `JobView`, `RunView`,
  `CompanyView`.
- `dev.shoaib.jobradar.web.*Test` — one `@WebMvcTest` class per controller.
- `src/main/resources/static/index.html` — single-file vanilla JS dashboard.

## Endpoint decisions
- **`GET /api/matches`**: `strength`/`status` are parsed case-insensitively via
  `Enum.valueOf(type, raw.toUpperCase())`; an invalid value throws
  `ResponseStatusException(BAD_REQUEST)` (parsed manually rather than relying on Spring's
  default enum converter, which is case-sensitive and wouldn't accept `match`/`active`).
  `minScore` is left as a plain `Double` `@RequestParam` — Spring's default
  `MethodArgumentTypeMismatchException` handling already 400s on a non-numeric value, so
  no custom handling was needed there. For each `MatchResultEntity` the matching
  `JobRecordEntity` is looked up by `jobId`; if it's ever missing (shouldn't happen given
  the FK, but defensive) that row is silently dropped rather than 500ing the whole list.
- **`GET /api/jobs/{id}`**: 404 via `ResponseStatusException` when absent;
  `techTags`/`benefits` parsed into `List<String>` (empty list, not null, when the column
  is null/blank).
- **`GET /api/runs?limit=`**: default 20, clamped to a max of 200 (not in the spec, added
  as a simple guard against an accidental huge query param) using
  `ingestRunRepository.findAllByOrderByIdDesc(PageRequest.of(0, limit))` as instructed.
  `stats` parsed into `Map<String, Object>`.
- **`GET /api/companies`**: one `CompanyView` per `CompanyEntry`, with
  `jobRecordRepository.countByCompanyIgnoreCaseAndStatus(entry.name(), JobStatus.ACTIVE)`
  for `activeCount`.
- **`POST /api/run`**: calls `ingestRunner.runOnce()` (autowired purely by the
  `core.IngestRunner` interface, per phase0 notes — no dependency on pipeline-notify's
  concrete bean) and returns the **full parsed `RunView`** (looked up via
  `ingestRunRepository.findById(runId)`), not just `{"runId": ...}`. Chose the richer
  response because the dashboard's "Run now" button can use it directly to refresh the
  last-run banner without a second round trip. Synchronous/blocking, as explicitly
  permitted by the spec for this personal-tool use case.

## Dashboard
Single `static/index.html`, vanilla JS + `fetch`, no build step, no external CSS/JS. Uses
CSS custom properties + `prefers-color-scheme` for a basic light/dark look. Sections:
last-run banner (from `GET /api/runs?limit=1`, showing `finishedAt`/`startedAt` and the
parsed `stats` map as key/value chips) with a "Run now" button (`POST /api/run`, disables
itself while in flight, then refreshes banner + table + companies panel); a matches panel
with strength/minScore/status filter controls wired to `GET /api/matches` query params and
a client-side sortable table (click any header to sort ascending/descending, data already
fetched — no re-fetch on sort); a companies/sources panel from `GET /api/companies`
(simpler to wire up than parsing per-source keys out of the `stats` JSON, whose exact key
shape is owned by pipeline-notify and isn't part of any frozen contract available to this
agent).

Java/Go flags in the matches table are derived client-side from each match's `reasons`
array (substring match on `"java"` / `"go-present"`/`"go"`), since `MatchView` doesn't
carry `techTags` — `reasons` entries are expected to align with the `app.match.weights`
keys in `application.yml` (`java-in-title`, `java-in-body`, `go-present`, ...). This is a
soft heuristic for a visual flag only, not used for filtering/scoring.

## Spring Boot 4.0.7 classpath discoveries (not covered in phase0 notes)
Verified directly against the jars under `~/.m2/repository` rather than guessing:
- **`@MockBean` is gone.** `spring-boot-test-4.0.7.jar` has no
  `org.springframework.boot.test.mock.mockito` package at all. The replacement is
  **`org.springframework.test.context.bean.override.mockito.MockitoBean`**, shipped in
  `spring-test-7.0.8.jar` (pulled in transitively via `spring-boot-starter-webmvc-test` ->
  `spring-boot-starter-test`). All controller tests use `@MockitoBean` from that package.
- **`@WebMvcTest` moved packages**, not just `spring-boot-starter-web` ->
  `-webmvc`: it now lives at
  **`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`** (in the new
  `spring-boot-webmvc-test` module), not the old
  `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`. `MockMvc` itself
  (`org.springframework.test.web.servlet.MockMvc` and friends) is unchanged.
- `JsonCodec` is a plain `@Component`, not a controller-layer bean, so `@WebMvcTest`'s
  slice doesn't pick it up automatically. Rather than mocking JSON (de)serialization in
  every test, each test class does `@Import(JsonCodec.class)` to register the real bean —
  it only needs the `ObjectMapper` the slice already auto-configures for MVC message
  conversion, so this exercises the actual parsing logic.

## Known gaps / deviations
None against the frozen contracts — `CompanyRegistry`, `IngestRunner`,
`JobRecordRepository`, `MatchResultRepository`, and the three persistence entities
provided exactly what Section 11 needed. No changes requested to any frozen file.
