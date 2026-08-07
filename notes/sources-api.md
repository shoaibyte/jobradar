# sources-api build notes

Scope covered: Part A (company registry loader), Section 4.1 (Arbeitnow), Section 4.4 (HN
"Who is hiring?"), Section 4.2/4.3/4.5 (Greenhouse/Personio/Lever generic ATS clients), and
Section 6 (`--app.detect-ats` command).

## Layout

- `sources/api/` (structured feeds, single global adapter per source):
  - `ArbeitnowAdapter` -- `SourceType.ARBEITNOW`, `@Component`.
  - `HnWhoIsHiringAdapter` -- `SourceType.HN_WHO_IS_HIRING`, `@Component`, `supportsRemovalDetection() = false`.
  - `TechGate` -- coarse Java/Go text-gate helper, used only to pre-filter HN comments (the
    `match` module has its own independent copy of the real gate).
- `sources/ats/` (company-registry-driven ATS clients + the registry itself):
  - `CompanyYamlLoader` -- shared SnakeYAML parsing of `companies:` into `List<CompanyEntry>`.
  - `YamlCompanyRegistry` -- the single `core.CompanyRegistry` `@Component` bean.
  - `GreenhouseAdapter` / `PersonioAdapter` / `LeverAdapter` -- **not** `@Component`s themselves;
    one instance of each is registered as its own Spring bean per matching `companies.yml` row
    (see below), so each shows up individually in `List<JobSourceAdapter>` as e.g.
    `"greenhouse:paypay"`.
  - `AtsAdapterConfig` + `AtsAdapterRegistrar` -- an `@Configuration` importing an
    `ImportBeanDefinitionRegistrar` that reads `companies.yml` at bean-*definition* time and
    registers one `GenericBeanDefinition` per greenhouse/personio/lever row. This was necessary
    because a `@Bean List<JobSourceAdapter>` method produces a single bean of type `List`, which
    Spring's collection-autowiring does *not* flatten into other components' `List<JobSourceAdapter>`
    injection points (it only aggregates beans individually assignable to the element type) --
    so genuinely dynamic per-company beans require bean-definition-time registration, not a
    `@Bean` factory method. `ImportBeanDefinitionRegistrar` (rather than a component-scanned
    `BeanDefinitionRegistryPostProcessor`) was chosen because Spring explicitly documents and
    guarantees `EnvironmentAware` callbacks for it before `registerBeanDefinitions()` runs.
  - `AtsDetector` (pure, unit-testable) + `AtsDetectRunner` (`ApplicationRunner`, `@Component`)
    -- `--app.detect-ats=<url>` fetch/probe/print/exit command.

## What actually gets instantiated from the current (frozen) `companies.yml`

- `greenhouse:paypay`, `greenhouse:paypaycard`, `greenhouse:paypaysec` (3 Greenhouse beans).
- `personio:verimi`, `personio:journi-gmbh` (2 Personio beans).
- 0 Lever beans (no `ats: lever` rows exist yet; `LeverAdapter` is fully implemented and tested
  against `fixtures/lever/sample-postings.json` so it activates automatically the moment a
  `lever` row is added, per the task's own note that no live company is wired to it).
- Picnic/HENNGE (`ats: custom-html`) and the commented-out Vinted row are deliberately skipped by
  `AtsAdapterRegistrar` (`default` case) -- those belong to `sources-html`.

## Known gaps / deviations (nothing here changes a frozen contract, just documenting judgment calls)

1. **`JobRecordRepository` detail-page politeness was not wired into any of these adapters.**
   Section 4.1/4.2/4.3/4.5 as given to this agent all fetch a single bulk endpoint that already
   returns full content inline (Arbeitnow's listing API, Greenhouse's `?content=true`, Personio's
   `jobDescriptions` blocks, Lever's `description`/`descriptionPlain`) -- there is no separate
   "detail page" fetch to skip for already-seen externalIds. HN also needs no detail page (the
   comment itself is the full listing). The politeness pattern almost certainly matters for
   `sources-html` (Relocate.me/japan-dev/tokyodev, which do have listing+detail HTML page pairs),
   not for any adapter in this agent's scope.
2. **Country codes are best-effort.** Arbeitnow defaults to `DE` with a small hint map for a
   handful of neighboring-country name spellings that might appear in `location`, per the task's
   own acknowledgment that this is "ambiguous." Greenhouse/Personio/Lever leave `country` as
   `null` -- their location fields (`"Hybrid"`, `"Remote"`, office city names, free-text) don't
   reliably encode a country, and the task didn't specify a mapping; `country` is left for a
   downstream consumer (e.g. via the company's known HQ) rather than guessed here.
3. **Required-field hardening added beyond the literal spec text.** Each ATS client treats a
   missing identity field (Greenhouse/Lever `id`, Personio `<id>`, Arbeitnow `slug`) as a
   malformed entry to skip (throw + caught by the per-item try/catch) rather than emit a
   `JobPosting` with a blank externalId, since a posting with no stable identity would break
   `JobRecordRepository`'s dedupe key downstream. This is what the "one bad entry doesn't kill
   the batch" tests exercise concretely (Jackson's tree API is otherwise so defensive that
   almost nothing else in these parsers can actually throw per-item).
4. **`AtsDetector`'s company-name guess from `<title>`** is a rough heuristic (split on
   `|`/`-`/`:`, drop obvious "careers"/"job" tokens, keep the longest remainder). It won't always
   produce a clean company name (e.g. a bare `<title>Careers at Acme</title>` with no separator
   returns the whole phrase) -- the printed companies.yml snippet is meant to be pasted and
   hand-edited, not used verbatim.
5. **HN's coarse pre-filter can't distinguish a job-seeker's post from a company posting** (see
   `HnWhoIsHiringAdapterTest.javaJobSeekerCommentStillMatchesTheCoarseFilter`) -- by design, per
   the task framing this as a coarse pre-filter with the real precision gate living in `match`.

## Testing approach

- Greenhouse/Personio/Lever: WireMock (`WireMockServer` + real fixture file bodies) for the full
  `fetch()` HTTP path, plus direct calls to each adapter's package-private `parse(...)` method
  for the synthetic/empty Personio fixtures and malformed-entry resilience cases (avoids a second
  WireMock server per edge case).
- Arbeitnow/HN: fixture JSON fed directly into package-private pure parsing methods
  (`parsePage`/`parseCommentsPage`) plus a tiny in-memory `HttpFetcher` stub for pagination-limit
  tests -- no WireMock needed, per the task's "whichever is less code" guidance.
- `CompanyYamlLoader`/`YamlCompanyRegistry`: one suite drives synthetic YAML strings through the
  loader (defaults, malformed-row skipping, enum-parsing failures); the other exercises the real,
  frozen `config/companies.yml` end-to-end as a registry sanity check.
- `AtsDetector`: pure unit tests against canned HTML snippets (no network). `AtsDetectRunner`
  only has a test for the blank-`app.detect-ats` no-op path -- the non-blank path calls
  `System.exit(...)`, which would tear down the whole Surefire JVM if exercised under a test
  runner, so that path is intentionally left to manual/orchestrator verification.
