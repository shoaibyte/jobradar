package dev.shoaib.jobradar.sources.html;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.JobPosting;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class JapanDevAdapterTest {

    private static final JapanDevProperties PROPS = new JapanDevProperties(
        true, "https://japan-dev.com", List.of("/jobs", "/java-jobs-in-japan", "/go-jobs-in-japan"));

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);
    private static final FetchContext CTX = new FetchContext(null, null, CLOCK);

    private final JapanDevAdapter adapter = new JapanDevAdapter(PROPS);

    private static String fixture(String name) {
        try (InputStream in = JapanDevAdapterTest.class.getClassLoader()
            .getResourceAsStream("fixtures/japandev/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesNuxtDataFromJobsPageAndDropsResidentsOnlyListings() throws Exception {
        List<JobPosting> postings = adapter.parseNuxtPage(fixture("jobs.html"), "https://japan-dev.com/jobs", CTX);

        assertThat(postings).isNotEmpty();
        // Every returned posting must have been eligible to apply from abroad.
        assertThat(postings).allSatisfy(p -> assertThat(p.country()).isEqualTo("Japan"));

        JobPosting moneyForward = postings.stream()
            .filter(p -> p.externalId().equals("3571"))
            .findFirst()
            .orElseThrow();
        assertThat(moneyForward.title()).isEqualTo("Security Engineer, CQO Office, Tokyo");
        assertThat(moneyForward.company()).isEqualTo("Money Forward");
        assertThat(moneyForward.city()).isEqualTo("Tokyo");
        assertThat(moneyForward.visaFlag()).isTrue();
        assertThat(moneyForward.techTags()).contains("Python");
        assertThat(moneyForward.salaryRaw()).contains("6,408,000").contains("11,004,000");
        assertThat(moneyForward.url()).isEqualTo("https://japan-dev.com/jobs/money-forward/money-forward-security-engineer-cqo-office-tokyo-72m1f7");
    }

    @Test
    void everyReturnedJobHasVisaFlagTrueSinceJapanOnlyListingsAreDropped() throws Exception {
        List<JobPosting> postings = adapter.parseNuxtPage(fixture("jobs.html"), "https://japan-dev.com/jobs", CTX);
        assertThat(postings).allSatisfy(p -> assertThat(p.visaFlag()).isTrue());
    }

    @Test
    void parsesJavaJobsCategoryPage() throws Exception {
        List<JobPosting> postings = adapter.parseNuxtPage(
            fixture("java-jobs-in-japan.html"), "https://japan-dev.com/java-jobs-in-japan", CTX);
        assertThat(postings).isNotEmpty();
    }

    @Test
    void parsesGoJobsCategoryPage() throws Exception {
        List<JobPosting> postings = adapter.parseNuxtPage(
            fixture("go-jobs-in-japan.html"), "https://japan-dev.com/go-jobs-in-japan", CTX);
        assertThat(postings).isNotEmpty();
    }

    @Test
    void oneBadHitDoesNotSinkTheWholePage() throws Exception {
        // A minimal, hand-built __NUXT_DATA__ devalue array with two job hits: one
        // missing its required "id" field (duck-typed as a hit since it still has
        // "title"/"company_name", but malformed) and one fully valid.
        String json = "["
            + "[1,4],"
            + "{\"title\":2,\"company_name\":3},"
            + "\"Bad Hit\","
            + "\"Some Co\","
            + "{\"id\":5,\"title\":6,\"company_name\":7,\"candidate_location\":8,\"slug\":9,\"company\":10},"
            + "\"42\","
            + "\"Good Job\","
            + "\"Good Co\","
            + "\"candidate_location_anywhere\","
            + "\"good-job-slug\","
            + "{\"slug\":11},"
            + "\"good-co\""
            + "]";
        String html = "<html><body><script type=\"application/json\" data-nuxt-data=\"nuxt-app\" data-ssr=\"true\" "
            + "id=\"__NUXT_DATA__\">" + json + "</script></body></html>";

        List<JobPosting> postings = adapter.parseNuxtPage(html, "https://japan-dev.com/jobs", CTX);
        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).externalId()).isEqualTo("42");
        assertThat(postings.get(0).title()).isEqualTo("Good Job");
    }

    @Test
    void returnsNullWhenNuxtDataScriptIsMissingSoCallerFallsBackToDom() throws Exception {
        String html = "<html><body><p>no nuxt data here</p></body></html>";
        List<JobPosting> postings = adapter.parseNuxtPage(html, "https://japan-dev.com/jobs", CTX);
        assertThat(postings).isNull();
    }
}
