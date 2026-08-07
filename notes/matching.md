# match agent notes

## What was built
- `src/main/java/dev/shoaib/jobradar/match/MatchProperties.java` - `@Component
  @ConfigurationProperties(prefix = "app.match")` binding for the whole `app.match.*` block.
  Written as a classic mutable JavaBean (nested static classes, getters/setters) rather than a
  record with constructor binding, since this bean is picked up by plain component scanning
  (no `@EnableConfigurationProperties`/`@ConfigurationPropertiesScan` allowed - `JobRadarApplication`
  is frozen). That's the documented Spring Boot alternative: any bean annotated with both
  `@Component` and `@ConfigurationProperties` gets bound by the auto-configured
  `ConfigurationPropertiesBindingPostProcessor` regardless of how it was registered.
- `src/main/java/dev/shoaib/jobradar/match/CandidateFitMatchEngine.java` - the single
  `@Component` bean implementing `core.MatchEngine`. Applies role -> tech -> visa gates in that
  order, then scores.
- `src/test/java/dev/shoaib/jobradar/match/CandidateFitMatchEngineTest.java` - the required
  Section 11 table, run as plain JUnit 5 (no Spring context): `MatchProperties` is built by hand
  to mirror `application.yml` exactly, and `CompanyRegistry` is a small in-test fake seeded from
  the real `config/companies.yml` values.

## Scoring implementation choices worth flagging

**Experience-range parsing** (`parseExperienceRange` in `CandidateFitMatchEngine`): three regexes
tried in order of specificity against the free-text description, deliberately simple per the
spec's own "don't over-engineer" guidance:
1. `N-M years` / `N to M years` (also accepts en/em dash and the `yrs`/`yr` abbreviation) -> range `[min(N,M), max(N,M)]`.
2. `N+ years` -> range `[N, 99]` (open-ended above).
3. Bare `N years` -> range `[N, N]` (treated as an exact point).
First match wins; "overlap" is then a plain interval-intersection check against
`experience.target-min-years`/`target-max-years` (`range[0] <= targetMax && range[1] >= targetMin`).
Note the `yrs`/`yr` abbreviation support isn't explicitly requested by the spec text but is a
one-token addition to the same regex to catch obviously-real-world phrasing (e.g. "2-5 yrs") without
adding a fourth pattern.

**Relocation-benefit scoring** - the spec text ("+1 per relocation benefit (cap 3, from
`JobPosting.benefits()` list size)") is ambiguous between "count every item in `benefits()`,
capped at 3" and "count only the relocation-flavored items in `benefits()`, capped at 3". I chose
the latter: a benefit line counts only if it case-insensitively contains one of
`reloc`/`visa`/`immigration`/`moving`/`housing`. Rationale: scoring "every benefit" regardless of
content (free lunch, gym membership, etc.) as if it were relocation-specific didn't seem faithful
to the rule's name. **This is a judgment call, not a spec-mandated behavior - flagging it in case
the intended reading was the simpler `min(benefits().size(), cap)`.**

**Seniority down-ranks are independent, not mutually exclusive** - if a title matches both the
heavy list (Principal/Staff/Architect/Director/Head) and the mild list (Lead/Manager), e.g. "Lead
Architect", both penalties apply (-3 and -1) rather than picking one. The spec lists them as two
separate bullet-point rules like all the other additive rules, so this follows that pattern rather
than introducing an implicit precedence the spec doesn't state.

**Role gate checks only the title**, not the description, matching the spec's literal wording
("reject if title contains any of the configured exclude keywords").

**Go detection** (`detectGo`) has three independent paths, matching the spec exactly:
1. `\bgolang\b` anywhere (title/description/tags).
2. `\bgo\b` as a whole token inside any `techTags` entry (tags are inherently a deliberate
   tech-stack signal, not ambiguous prose, so no proximity requirement there).
3. `\bgo\b` in the *description* only, gated by a real proximity-window scan: for every bare "go"
   match, take the `[start-proximity, end+proximity]` substring window and test it against each
   pre-compiled `\b<context-word>\b` pattern. Covered by a dedicated test
   (`goodToGoProse_doesNotTriggerTechGate`) using the exact "good to go" phrase from the spec,
   with a description that contains none of the context words anywhere at all (not just outside
   the window), so the test is robust to the exact proximity-chars value.

## Company registry test fixture
Per instructions, the test uses a hand-built fake `CompanyRegistry`, not the real YAML-backed
implementation (owned by `sources-api`). Values were taken directly from `config/companies.yml`:
- PayPay, Picnic: `COMPANY_WIDE` + `HIGH`.
- Verimi: **no** `relocation:`/`priority:` keys in the real file -> defaults to `NONE`/`NORMAL`
  (per `CompanyEntry`'s documented default). The Verimi test case therefore passes the visa gate
  via the `visa-gate.description-pattern` regex match on the posting description ("visa
  sponsorship" / "relocation support"), not via the registry - confirmed by reading the actual
  YAML entry as instructed rather than assuming COMPANY_WIDE.
- HENNGE: also no relocation/priority keys -> `NONE`/`NORMAL`.
- Journi: `priority: HIGH` only, no `relocation:` key -> `NONE`/`HIGH`.
- Vinted: commented out in the real file (no discoverable Greenhouse board token) - intentionally
  absent from the fake registry too.

## Known gaps / non-issues
- No frozen contract needed changing; nothing to escalate there.
- `parseExperienceRange` and the go-proximity window scan are private methods exercised only
  indirectly through `MatchEngine.evaluate()` in the table-driven tests (positive and negative
  cases for both), not via standalone unit tests - kept the test surface to what the interface
  exposes rather than adding reflection-based tests for private helpers.
- All non-benefits-list description-derived scoring (Spring/Kotlin/Scala mentions, experience
  overlap) is scanned across `description` + `techTags`; `title` is included separately only for
  the Java-in-title signal, to keep "Java in title" vs "Java in tags/stack/description" as the two
  genuinely independent signals the spec calls out.
