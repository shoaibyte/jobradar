# sources-html agent notes

## What was built

All five Section 5 adapters, each a `@Component` implementing `JobSourceAdapter`, under
`src/main/java/dev/shoaib/jobradar/sources/html/`:

- `RelocateMeAdapter` (+ `RelocateMeProperties`) — `SourceType.RELOCATE_ME`
- `JapanDevAdapter` (+ `JapanDevProperties`, `NuxtDevaluePayload`) — `SourceType.JAPAN_DEV`
- `TokyoDevAdapter` — `SourceType.TOKYO_DEV`
- `PicnicAdapter` — `SourceType.CUSTOM_HTML`, `name()` = `custom-html:picnic`
- `HenngeAdapter` — `SourceType.CUSTOM_HTML`, `name()` = `custom-html:hennge`

Fixture-backed JUnit 5 tests for all five live under
`src/test/java/dev/shoaib/jobradar/sources/html/` (23 tests total). Each adapter exposes
its listing/detail parsing as package-private methods precisely so tests can feed
fixture HTML straight in via jsoup, with no network access and no Spring context.

Every adapter wraps per-job-card parsing in try/catch (log + skip); `RelocateMeAdapter`,
`JapanDevAdapter`, `TokyoDevAdapter`, and `PicnicAdapter` have an explicit test proving a
malformed/off-shape card next to a good one doesn't lose the good one.

**Naming convention adopted:** `name()` returns a lowercase source key
(`"relocateme"`, `"japandev"`, `"tokyodev"`, `"custom-html:picnic"`,
`"custom-html:hennge"`), matching the `JobSourceAdapter.name()` javadoc example
(`"greenhouse:paypay"`) and the pattern already used by the `sources-ats` agent's
`GreenhouseAdapter`. This same string is used as `JobPosting.sourceName` and as the
`source` argument to `JobRecordRepository.findBySourceAndExternalId` — not
`SourceType.name()` — because Picnic and HENNGE both map to `SourceType.CUSTOM_HTML` and
would otherwise collide in the `(source, external_id)` unique constraint and in
per-source removal-detection queries.

**Config binding:** `RelocateMeProperties`/`JapanDevProperties` are
`@ConfigurationProperties`-annotated records marked `@Component` (constructor binding is
implicit for records), registered via plain component scanning rather than
`@ConfigurationPropertiesScan`/`@EnableConfigurationProperties` on the frozen
`JobRadarApplication` root — this is a documented, supported Spring Boot pattern.
TokyoDev/Picnic/HENNGE only need two scalar properties each, so they use constructor
`@Value` injection instead, matching `ArbeitnowAdapter`'s style.

**Verification performed:** `mvnw`/any build tool was never invoked (per the task's
constraints). Instead, every fixture-file selector/regex/JSON-resolution path was
smoke-tested against the real fixture bytes with standalone `javac`+ jsoup/jackson jars
from `~/.m2`, and — as a final check — all five main classes plus all five test classes
were compiled and *run* together (via a throwaway `junit-platform-launcher` runner in a
scratch directory, against the actual jars this project's `pom.xml` resolves to) with all
23 tests passing. This is not a substitute for the orchestrator's real Maven build, but
it caught several real bugs before hand-off (a stray malformed import, an accidental
`\visa\b` false-positive in a hand-written test fixture, etc.).

## Section 5.1 — Relocate.me

Matches the spec closely. Listing cards (`div.jobs-list__job`) link to
`/{country}/{city}/{company}/{slug}-{id}`; `{company}` slug is checked against
`excluded-slugs` before any further parsing. Detail pages: `h1` for title,
`.job-info__country p` splits on `,` into city/country, `.job-info__company a` for
company, `.relocation-packages__item span` for benefits, `.job__tag` for techTags,
`.job-info__description-item` sections concatenated for description. `visaFlag` is true
when any benefit contains "visa" (case-insensitive) or the relocation-packages heading
(always "Advanced relocation package" in the fixtures) mentions "relocation".

One deviation from the spec text worth flagging: there is no separate "tech-stack
block" in the real DOM — the tech stack (e.g. "Java, Kotlin, Scala / Spring Boot,
JUnit...") is free text inside the "Position" `.job-info__description-item`, not its own
element. `techTags` instead comes from the keyword-tag strip near the bottom
(`.job-info__tags a.job__tag`), which is what the spec's "keyword tags near the bottom"
line actually describes; the tech stack itself is folded into `description`.

Pagination is coded as a generic "stop once a page adds no new job cards" loop per the
task's instructions (only page 1 has a fixture, so this is unverified against a real
page 2, but the loop degrades safely to a single page against `tryGet()` returning
empty).

## Section 5.2 — Japan Dev

Confirmed the phase0 note: the site is Nuxt, not Next.js. `NuxtDevaluePayload` is a
~90-line generic resolver for Nuxt's devalue-encoded `__NUXT_DATA__` array (index-based
references, with a small set of two-element `["ShallowReactive", n]`-style reducer tags
that transparently unwrap). Rather than hard-coding the path to the job list
(`data.algolia-state.Job_production.results[0].hits` in the `jobs.html` fixture — a path
that's specific to Nuxt/Algolia composable key names and could easily change), it
duck-types: it walks the fully-resolved tree looking for any list whose every element is
a map containing `title` and `company_name` keys. This was verified against all three
provided fixtures (`jobs.html`, `java-jobs-in-japan.html`, `go-jobs-in-japan.html`).

Key discovery made while building this: the JSON's `candidate_location` field
(`candidate_location_anywhere` vs `candidate_location_japan_only`) maps 1:1 with the
DOM's "Apply from Abroad" vs "Residents Only"/"Japan Only" badges (verified by counting
occurrences in the fixture: 21 "Apply from Abroad" badges = 21
`candidate_location_anywhere` hits; 39 "Residents Only"/"Japan Only" badges = 39
`candidate_location_japan_only` hits, out of 60 total). So `visaFlag` and the
residents-only drop are both driven directly by that one field, not by `sponsors_visas`
(which is a separate, less strongly-correlated field about the company's general visa
policy).

No per-job detail page is fetched for Japan Dev: the listing/category-page JSON already
carries every field the spec asks for (title, company, city, salary, skills, visa
status) except a long-form description, and `intro`/`details`/`requirements` are `null`
in the listing JSON itself (populated only on the individual job page, for which no
fixture exists). `description` is synthesized from the fields that are present (company
short description, seniority/employment/remote/language levels, company perks). The DOM
fallback (used only if `__NUXT_DATA__` is missing/unparseable) recovers materially less,
because the `technology-list` tag list renders empty server-side and is populated
client-side from this same JSON after Vue hydration — this was confirmed by inspecting
the raw fixture bytes (`<ul class="technology-list"><!--[--><!--]--></ul>`).

## Section 5.3 — TokyoDev

Job rows are grouped under one `<li>` per company; meta tags (salary, Japanese-level
requirement, remote-level, "Apply from abroad") are told apart from tech/role tags by
their `href` slug (a small fixed vocabulary under `/jobs/...`), not by text content or
position — verified against every unique tag href in the fixture
(`salary-data`, `japanese-required`, `no-japanese-required`, `residents-only`,
`apply-from-abroad`, `no-remote`, `partially-remote`, `fully-remote`; everything else,
including role tags like `quality-assurance`, is treated as a tech/role tag). Parsing
the listing recovers exactly 148 job cards, matching the page's own "148 positions
available" header — a strong signal the extraction has no misses/dupes.

**Known gap:** only `fixtures/tokyodev/jobs.html` (the listing page) was captured — no
job detail page fixture exists for TokyoDev. Per the spec ("Follow detail links for
description on first sight only"), the adapter still fetches the detail page via
`ctx.http().tryGet()` for jobs not already known to `JobRecordRepository`, but the
extraction logic (`extractDescription`, generic `article/main/[class*=description]`
selectors) is written defensively and is **unverified against real TokyoDev detail
markup**. If TokyoDev's detail page shape turns out to need something more specific,
that method is the one to revisit.

## Section 5.4 — Picnic

`jobs.picnic.app` is a Next.js App Router site whose vacancy list is not present as
plain server-rendered anchors — it's streamed as React Server Component ("flight") data:
`self.__next_f.push([1,"<escaped JSON string>"])` script chunks. One matched-and-verified
regex (`PicnicAdapter.EMBEDDED_RECORD`) extracts each vacancy record
(`{"data":{"location":{...}},"_id":...,"name":...,"url":...}`) after a single
backslash-quote unescape pass; this recovered exactly the 11 unique Engineering vacancies
present in the fixture (cross-checked independently via a Python script against the raw
file). A handful of "featured" vacancies also render as plain anchors
(`<a href="/en/vacancies/...">`), so the adapter merges both extraction paths.

The spec's assumed URL shape was `/en/vacancies/{CODE}/{category}/{slug}`; the real
fixture's URLs have three additional trailing segments (`/{city}/{state}/{country}`).
Category filtering (Engineering/Technology) uses the URL's category segment directly,
matching the spec's own phrasing.

**Known gap / spec deviation:** the spec states "Picnic states its relocation package
(visa sponsorship, flight to Amsterdam, first month's accommodation, 30%-ruling help)
directly in postings." The real captured detail fixture
(`software-engineer-logistics-detail.html`) does **not** do this — grepping the fixture
for "visa", "sponsor", "flight", "accommodation", "ruling", "housing" turns up nothing;
the posting's only relocation-related content is a "🌍 Benefits for expats" paragraph
that *links out* to a separate `/en/relocation` page rather than stating the package
inline. Separately, most of the detail page's body (responsibilities/requirements) is
also flight-payload data — a deeply nested serialized React element tree, not plain
HTML/JSON — which a regex/jsoup pass cannot faithfully reconstruct in the time available.
`PicnicAdapter.parseDetailPage` therefore builds `description` from what *is* reliably
present (the SEO `<meta name="description">`, plus any directly-embedded rich-text HTML
fragments — Recruitee-style content blocks are, unlike the surrounding page chrome,
shipped as literal escaped HTML strings, e.g. the "Picnic Perks" section, which decodes
cleanly). This still correctly detects `visaFlag=true` and a `benefits` entry for the
one real posting, because that posting's text does contain the word "relocation" (in
"...if you want to find our relocation benefits, see here") — but the `benefits` list
will be sparser than the spec implies for postings that don't even mention that. Since
`config/companies.yml` already marks Picnic `relocation: COMPANY_WIDE`, downstream
matching does not depend on this adapter getting `visaFlag` right per-posting.

## Section 5.5 — HENNGE

**Known gap (the significant one):** `app.sources.hennge.base-url` is frozen at
`https://hennge.com/global/recruit/`, and the captured fixture
(`fixtures/hennge/recruit.html`) for that exact URL is HENNGE's own WordPress "Not
Found" page (`<title>Not Found | HENNGE...</title>`, `<h2>Not Found</h2>`, "The URL
requested could not be found on this server."). Grepping the whole fixture for
"python"/"go"/"backend"/"infrastructure"/"mid-career" turns up nothing. The real
careers listing lives on an entirely different domain,
`https://recruit.hennge.com/en/` (linked from that 404 page's own footer under
"Careers at HENNGE"), for which no fixture was captured. Since `application.yml` is
frozen and out of scope to edit, and no network access is available to this agent to
capture a fixture from the real domain, `HenngeAdapter` cannot be verified against real
HENNGE careers markup.

The adapter is written to degrade safely: `fetch()` parses generically (looking for
`article`/`.job-list__item`/similar cards with a heading and a link), explicitly treats
a "Not Found" heading as "not a job" (so the 404 fixture correctly yields an empty list,
not a fabricated posting), and returns an empty list with a warning log rather than
throwing when nothing recognizable is found — verified by an end-to-end test that calls
the real `fetch()` against the actual fixture bytes with a mocked `HttpFetcher`. A
second test exercises the same `fetch()` path against a synthetic, plausibly-shaped
listing to confirm the title/company/techTags/visaFlag extraction logic itself works
once given real markup to work with.

The one fully-detailed, real HENNGE posting anywhere in the fixture set was captured via
Relocate.me (`fixtures/relocateme/hennge-senior-software-engineer-backend-infrastructure.html`)
and is exercised by `RelocateMeAdapterTest`, not here — that's the actual live HENNGE
job data available to the match module today; a HENNGE-specific fixture capture from
`recruit.hennge.com` would be the natural follow-up if this source needs to stand on its
own.

## Files

Main: `src/main/java/dev/shoaib/jobradar/sources/html/{RelocateMeAdapter,
RelocateMeProperties, JapanDevAdapter, JapanDevProperties, NuxtDevaluePayload,
TokyoDevAdapter, PicnicAdapter, HenngeAdapter}.java`

Tests: `src/test/java/dev/shoaib/jobradar/sources/html/{RelocateMeAdapterTest,
JapanDevAdapterTest, TokyoDevAdapterTest, PicnicAdapterTest, HenngeAdapterTest}.java`

No files outside `sources/html/**` (main or test) were created or modified; no frozen
file (`pom.xml`, `application.yml`, `core/**`, `db/migration/**`,
`config/companies.yml`, any fixture) was touched.

## RelocateMeSubstackAdapter (The Global Move newsletter)

Added later (2026-08): scrapes relocate.me's Substack, `relocateme.substack.com`,
where a hand-curated job-list issue ("Weekly Hand-Curated Tech Jobs With Relocation:
Week N") is published every Thursday. No JS rendering is needed — Substack's public
JSON API serves everything:

- `GET /api/v1/archive?sort=new&offset=N&limit=K` — issue discovery (slug, post_date,
  postTags, audience). Newest-first; paging stops at `lookback-days` or `max-posts`.
  Allowed by Substack's robots.txt (`/api/` is not in the `User-agent: *` disallow set).
- `GET /api/v1/posts/{slug}` — `body_html` carries the rendered post body.

Issue bodies group jobs under `<h2>` section headings ("Back End", "Full Stack",
"Front End", ...); each entry is `li > p > strong > a` (title + apply URL) with a
nested `ul` of labeled bullets (`Company:`, `Location:`, `Industry and size:`,
`Job keywords:`, `Time zone:`, relocation lines). Verified against the live free issue
`work-from-anywhere-tech-jobs-introductory`; fixtures mirror that exact markup.

Design points:

- **externalId = canonicalized apply URL** (tracking params `utm_*`/`ref`/`source`/
  `src`/`embed` stripped, `gh_jid`/`ashby_jid`-style params kept), so a job re-listed
  across weeks dedupes; newest issue wins within one fetch, and the (source,
  external_id) upsert handles cross-run continuity.
- **Paywall**: weekly issues are `audience: only_paid`; anonymous requests get a free
  preview whose body contains only a section index (zero entries). The adapter detects
  this and logs a pointer to `SUBSTACK_COOKIE` (host→Cookie map in
  `app.http.auth.cookies`, sent by `ThrottledHttpFetcher`), which a paid subscriber can
  set to unlock the content they're entitled to. Free posts parse without it.
- **Layout-drift fallback**: if none of the configured `sections` headings appear, every
  job-shaped `li` in the body is swept and kept when title/keywords match
  `fallback-title-pattern` (backend-ish regex).

Main: `sources/html/{RelocateMeSubstackAdapter, RelocateMeSubstackProperties}.java`,
plus `pipeline/HttpAuthProperties.java` and the Cookie hook in `ThrottledHttpFetcher`.
Tests: `RelocateMeSubstackAdapterTest` + `fixtures/relocateme-substack/*.json`.

### Paid-list hardening (second pass)

`parsePost` now returns a `PostParseResult` (postings + reconciliation inputs) rather
than a bare list, and `fetch()` routes it through `logPostDiagnostics`:

- **Advertised-count reconciliation.** Every issue's free intro indexes its own
  sections with totals ("Back End – 33 roles (incl. 8 remote)" linking to
  `.../i/<postid>/<anchor>`); `advertisedSectionCounts` parses those (also present in
  anonymous previews) and the diagnostics compare them against per-section parsed
  counts. Shortfall on a fully-served body → WARN naming the exact gap ("back end: got
  2 of 5") — the silent-job-loss failure mode is the one a job alerter can't afford.
- **Auth-state-aware paywall handling.** The adapter now injects `HttpAuthProperties`
  and knows whether a cookie is configured for the newsletter host: preview + cookie →
  WARN "expired/malformed, re-export"; preview + no cookie → INFO with the exact role
  inventory behind the paywall (live: ~170 backend roles across a 6-week window).
- **Cookie hygiene.** `HttpAuthProperties.cookieFor` trims and strips wrapping quotes
  and trailing `;` (env-var paste accidents); multi-cookie values pass through intact.
  Covered by `HttpAuthPropertiesTest`.

Deliberately NOT built: anything that circumvents the paywall. The adapter only
authenticates as the deployment owner's own paid account. Alternatives considered and
rejected: Substack private RSS feeds (disallowed by robots.txt for `/feed/private`,
which `ThrottledHttpFetcher` honors) and IMAP ingestion of the newsletter emails
(legitimate but a whole new subsystem; revisit if cookie rot becomes a nuisance).

Diagnostics are asserted in tests via a logback `ListAppender` on the adapter's logger
(`RelocateMeSubstackAdapterTest.captureLogs`). New fixtures: `post-weekly-shortfall.json`
(advertises 5, carries 2) and updated `post-weekly-fd0.json` (index totals match its
entries so the happy path asserts "all advertised section totals met").
