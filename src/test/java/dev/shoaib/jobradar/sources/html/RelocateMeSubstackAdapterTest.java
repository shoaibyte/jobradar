package dev.shoaib.jobradar.sources.html;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.HttpFetcher;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.SourceType;
import dev.shoaib.jobradar.pipeline.HttpAuthProperties;
import dev.shoaib.jobradar.sources.html.RelocateMeSubstackAdapter.PostParseResult;
import dev.shoaib.jobradar.sources.html.RelocateMeSubstackAdapter.PostRef;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RelocateMeSubstackAdapterTest {

    private static final String BASE = "https://relocateme.substack.com";

    private static final RelocateMeSubstackProperties PROPS = new RelocateMeSubstackProperties(
        true, BASE, 20, 6, 45,
        List.of("weekly-hand-curated-tech-jobs", "work-from-anywhere-tech-jobs"),
        List.of("Relocation-friendly jobs"),
        List.of("Back End", "Full Stack"),
        "(?i)back.?end|server[- ]side|platform engineer|software engineer|\\bjava\\b|golang|\\bgo\\b|python|node(\\.js)?|kotlin|scala|elixir|rust");

    private static final HttpAuthProperties NO_COOKIE = new HttpAuthProperties(Map.of());
    private static final HttpAuthProperties WITH_COOKIE = new HttpAuthProperties(
        Map.of("relocateme.substack.com", "substack.sid=test-session"));

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);

    private static final PostRef WEEK_78 = new PostRef(
        "weekly-hand-curated-tech-jobs-with-fd0",
        "Weekly Hand-Curated Tech Jobs With Relocation: Week 78",
        Instant.parse("2026-08-27T11:35:43.144Z"), "only_paid",
        List.of("job relocation", "Relocation-friendly jobs"));

    private final RelocateMeSubstackAdapter adapter = new RelocateMeSubstackAdapter(PROPS, NO_COOKIE);

    private static String fixture(String path) {
        try (InputStream in = RelocateMeSubstackAdapterTest.class.getClassLoader()
                .getResourceAsStream("fixtures/relocateme-substack/" + path)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Captures adapter log output for asserting the operator-facing diagnostics. */
    private static ListAppender<ILoggingEvent> captureLogs() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RelocateMeSubstackAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    void parsesArchivePageWithDatesSlugsAndTags() throws Exception {
        List<PostRef> posts = adapter.parseArchivePage(fixture("archive-page1.json"));

        assertThat(posts).hasSize(4);
        assertThat(posts.get(0).slug()).isEqualTo("weekly-hand-curated-tech-jobs-with-fd0");
        assertThat(posts.get(0).postDate()).isEqualTo(Instant.parse("2026-08-27T11:35:43.144Z"));
        assertThat(posts.get(0).tags()).contains("Relocation-friendly jobs");
        assertThat(posts.get(1).slug()).isEqualTo("internal-mobility-as-an-alternative");
    }

    @Test
    void parsesConfiguredSectionsAndIgnoresFrontEnd() throws Exception {
        PostParseResult result = adapter.parsePost(fixture("post-weekly-fd0.json"), WEEK_78);
        List<JobPosting> postings = result.postings();

        // 3 Back End + 1 Full Stack; the Front End entry and the section-index bullets are skipped
        assertThat(postings).hasSize(4);
        assertThat(postings).noneMatch(p -> p.title().equals("Frontend Engineer"));
        assertThat(postings).allMatch(p -> p.source() == SourceType.RELOCATE_ME_SUBSTACK);
        assertThat(postings).allMatch(p -> p.sourceName().equals("relocateme-substack"));

        JobPosting adyen = postings.stream()
            .filter(p -> "Senior Java Engineer".equals(p.title())).findFirst().orElseThrow();
        assertThat(adyen.company()).isEqualTo("Adyen");
        assertThat(adyen.city()).isEqualTo("Amsterdam");
        assertThat(adyen.country()).isEqualTo("Netherlands");
        assertThat(adyen.techTags()).containsExactly("Java", "Spring Boot", "PostgreSQL", "Kafka");
        assertThat(adyen.visaFlag()).isTrue();
        assertThat(adyen.benefits()).anyMatch(b -> b.contains("Visa sponsorship"));
        assertThat(adyen.postedAt()).isEqualTo(WEEK_78.postDate());
        assertThat(adyen.description()).contains("Listed in \"Weekly Hand-Curated Tech Jobs With Relocation: Week 78\" (2026-08-27)");

        // remote role: no location, no relocation wording -> no visa flag
        JobPosting trustWallet = postings.stream()
            .filter(p -> "Senior Backend Engineer (Go)".equals(p.title())).findFirst().orElseThrow();
        assertThat(trustWallet.company()).isEqualTo("Trust Wallet");
        assertThat(trustWallet.visaFlag()).isFalse();
        assertThat(trustWallet.description()).contains("Time zone: You can work any hours from anywhere");
    }

    @Test
    void reconcilesParsedEntriesAgainstAdvertisedSectionTotals() throws Exception {
        PostParseResult result = adapter.parsePost(fixture("post-weekly-fd0.json"), WEEK_78);

        assertThat(result.sectionsFound()).isTrue();
        assertThat(result.paidPreview()).isFalse();
        assertThat(result.advertisedCounts())
            .containsEntry("back end", 3).containsEntry("full stack", 1).containsEntry("front end", 1);
        assertThat(result.parsedCounts())
            .containsEntry("back end", 3).containsEntry("full stack", 1)
            .doesNotContainKey("front end");

        ListAppender<ILoggingEvent> logs = captureLogs();
        adapter.logPostDiagnostics(WEEK_78, result);
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("all advertised section totals met");
        });
    }

    @Test
    void warnsWhenFullBodyParsesFewerEntriesThanAdvertised() throws Exception {
        PostRef week77 = new PostRef("weekly-hand-curated-tech-jobs-with-shortfall",
            "Weekly Hand-Curated Tech Jobs With Relocation: Week 77",
            Instant.parse("2026-08-20T11:30:00Z"), "only_paid", List.of("Relocation-friendly jobs"));
        PostParseResult result = adapter.parsePost(fixture("post-weekly-shortfall.json"), week77);

        assertThat(result.sectionsFound()).isTrue();
        assertThat(result.postings()).hasSize(2);
        assertThat(result.advertisedCounts()).containsEntry("back end", 5);
        assertThat(result.parsedCounts()).containsEntry("back end", 2);

        ListAppender<ILoggingEvent> logs = captureLogs();
        adapter.logPostDiagnostics(week77, result);
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("back end: got 2 of 5");
        });
    }

    @Test
    void paywalledPreviewWithoutCookieReportsInventoryAtInfo() throws Exception {
        PostParseResult result = adapter.parsePost(fixture("post-paywalled-preview.json"), WEEK_78);

        assertThat(result.postings()).isEmpty();
        assertThat(result.paidPreview()).isTrue();
        assertThat(result.advertisedCounts()).containsEntry("back end", 33);

        ListAppender<ILoggingEvent> logs = captureLogs();
        adapter.logPostDiagnostics(WEEK_78, result);
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("33 back end");
            assertThat(e.getFormattedMessage()).contains("SUBSTACK_COOKIE");
        });
    }

    @Test
    void paywalledPreviewDespiteConfiguredCookieWarnsAboutExpiry() throws Exception {
        RelocateMeSubstackAdapter authed = new RelocateMeSubstackAdapter(PROPS, WITH_COOKIE);
        PostParseResult result = authed.parsePost(fixture("post-paywalled-preview.json"), WEEK_78);

        ListAppender<ILoggingEvent> logs = captureLogs();
        authed.logPostDiagnostics(WEEK_78, result);
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("expired");
            assertThat(e.getFormattedMessage()).contains("Re-export");
        });
    }

    @Test
    void externalIdStripsTrackingParamsButKeepsMeaningfulOnes() throws Exception {
        List<JobPosting> postings = adapter.parsePost(fixture("post-weekly-fd0.json"), WEEK_78).postings();

        JobPosting trustWallet = postings.stream()
            .filter(p -> "Senior Backend Engineer (Go)".equals(p.title())).findFirst().orElseThrow();
        assertThat(trustWallet.externalId()).isEqualTo("https://jobs.ashbyhq.com/trust-wallet/f368ed72");

        JobPosting fingerprint = postings.stream()
            .filter(p -> "Senior Backend Software Engineer".equals(p.title())).findFirst().orElseThrow();
        assertThat(fingerprint.externalId()).isEqualTo("https://fingerprint.com/careers/jobs/apply?gh_jid=5680806004");
    }

    @Test
    void fallbackSweepCatchesLayoutDriftButOnlyBackendishRoles() throws Exception {
        PostRef drifted = new PostRef("weekly-hand-curated-tech-jobs-with-zzz",
            "Weekly Hand-Curated Tech Jobs With Relocation: Week 99",
            Instant.parse("2026-08-26T11:30:00Z"), "everyone", List.of("Relocation-friendly jobs"));

        PostParseResult result = adapter.parsePost(fixture("post-layout-drift.json"), drifted);

        assertThat(result.sectionsFound()).isFalse();
        assertThat(result.paidPreview()).isFalse();
        assertThat(result.postings()).hasSize(1);
        assertThat(result.postings().get(0).title()).isEqualTo("Senior Backend Developer – Node.js");
        assertThat(result.postings().get(0).company()).isEqualTo("Bitfinex");
    }

    @Test
    void fetchSelectsWeeklyPostsWithinLookbackAndDedupesAcrossIssues() throws Exception {
        List<String> requested = new ArrayList<>();
        HttpFetcher fake = new HttpFetcher() {
            @Override
            public String get(String url) {
                return tryGet(url).orElseThrow();
            }

            @Override
            public Optional<String> tryGet(String url) {
                requested.add(url);
                Map<String, String> routes = Map.of(
                    BASE + "/api/v1/archive?sort=new&offset=0&limit=20", fixture("archive-page1.json"),
                    BASE + "/api/v1/posts/weekly-hand-curated-tech-jobs-with-fd0", fixture("post-weekly-fd0.json"),
                    BASE + "/api/v1/posts/weekly-hand-curated-tech-jobs-with-347", fixture("post-weekly-347.json"));
                return Optional.ofNullable(routes.get(url));
            }
        };
        FetchContext ctx = new FetchContext(fake, null, CLOCK);

        List<JobPosting> postings = adapter.fetch(ctx);

        // never fetched: the non-job article, and the Week 44 issue outside the 45-day window
        assertThat(requested).noneMatch(u -> u.contains("internal-mobility"));
        assertThat(requested).noneMatch(u -> u.contains("jobs-with-034"));

        // 4 from week 78 + 1 unique from week 77; the re-listed Trust Wallet job dedupes
        assertThat(postings).hasSize(5);
        assertThat(postings).extracting(JobPosting::externalId).doesNotHaveDuplicates();

        JobPosting trustWallet = postings.stream()
            .filter(p -> p.externalId().equals("https://jobs.ashbyhq.com/trust-wallet/f368ed72"))
            .findFirst().orElseThrow();
        // newest issue wins: week 78's version (has tech tags beyond "Go, AWS")
        assertThat(trustWallet.postedAt()).isEqualTo(Instant.parse("2026-08-27T11:35:43.144Z"));
        assertThat(postings).anyMatch(p -> "Juju Software Engineer (Go)".equals(p.title()));
    }

    @Test
    void disabledAdapterFetchesNothing() throws Exception {
        RelocateMeSubstackProperties off = new RelocateMeSubstackProperties(
            false, BASE, 20, 6, 45, List.of(), List.of(), List.of(), null);
        RelocateMeSubstackAdapter disabled = new RelocateMeSubstackAdapter(off, NO_COOKIE);
        assertThat(disabled.fetch(new FetchContext(null, null, CLOCK))).isEmpty();
    }
}
