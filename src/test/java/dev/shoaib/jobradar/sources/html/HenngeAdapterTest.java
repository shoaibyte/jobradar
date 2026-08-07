package dev.shoaib.jobradar.sources.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.HttpFetcher;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * See notes/sources-html.md: the configured {@code app.sources.hennge.base-url} 404s in
 * reality, and the captured fixture is HENNGE's own "Not Found" page. These tests
 * mainly pin down the resilience contract (a moved/broken page yields an empty list,
 * never an exception) and exercise the generic best-effort card parser end-to-end
 * (through the real {@code fetch()} path, with a mocked {@code HttpFetcher}) against a
 * synthetic fixture shaped like a plausible careers listing.
 */
class HenngeAdapterTest {

    private static final String BASE_URL = "https://hennge.com/global/recruit/";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);

    private static String fixture() {
        try (InputStream in = HenngeAdapterTest.class.getClassLoader()
            .getResourceAsStream("fixtures/hennge/recruit.html")) {
            if (in == null) {
                throw new IllegalStateException("missing fixture");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private FetchContext ctxServing(String html) {
        HttpFetcher http = mock(HttpFetcher.class);
        when(http.tryGet(eq(BASE_URL))).thenReturn(Optional.ofNullable(html));
        return new FetchContext(http, null, CLOCK);
    }

    @Test
    void the404NotFoundFixtureYieldsAnEmptyListRatherThanThrowingOrFabricatingAJob() throws Exception {
        HenngeAdapter adapter = new HenngeAdapter(BASE_URL, true, mock(JobRecordRepository.class));

        List<JobPosting> postings = adapter.fetch(ctxServing(fixture()));

        assertThat(postings).isEmpty();
    }

    @Test
    void parsesASyntheticListingShapedLikeAPlausibleCareersPageEndToEnd() throws Exception {
        JobRecordRepository repo = mock(JobRecordRepository.class);
        when(repo.findBySourceAndExternalId(eq("custom-html:hennge"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        HenngeAdapter adapter = new HenngeAdapter(BASE_URL, true, repo);

        String html = """
            <html><body>
            <article class="job-item">
              <h2>Senior Backend Engineer (Python)</h2>
              <a href="https://recruit.hennge.com/en/jobs/senior-backend-engineer-python">Apply</a>
              <p>We are looking for a Senior Backend Engineer. Python is our primary language,
                 with Go as a nice-to-have. Visa sponsorship and relocation support available.</p>
            </article>
            <article class="job-item">
              <h2>SRE / Infrastructure Engineer</h2>
              <a href="https://recruit.hennge.com/en/jobs/sre-infrastructure-engineer">Apply</a>
              <p>Kubernetes, AWS, Terraform. Local candidates only, please.</p>
            </article>
            </body></html>
            """;

        List<JobPosting> postings = adapter.fetch(ctxServing(html));
        assertThat(postings).hasSize(2);

        JobPosting backend = postings.stream()
            .filter(p -> p.title().equals("Senior Backend Engineer (Python)"))
            .findFirst()
            .orElseThrow();
        assertThat(backend.company()).isEqualTo("HENNGE");
        assertThat(backend.techTags()).contains("Python", "Go");
        assertThat(backend.visaFlag()).isTrue();

        JobPosting sre = postings.stream()
            .filter(p -> p.title().equals("SRE / Infrastructure Engineer"))
            .findFirst()
            .orElseThrow();
        assertThat(sre.techTags()).contains("Kubernetes", "AWS", "Terraform");
        assertThat(sre.visaFlag()).isFalse();
    }

    @Test
    void oneUnparsableCardDoesNotSinkTheWholePage() {
        HenngeAdapter adapter = new HenngeAdapter(BASE_URL, true, mock(JobRecordRepository.class));
        String html = """
            <html><body>
            <article class="job-item"></article>
            <article class="job-item">
              <h2>Valid Role</h2>
              <a href="https://recruit.hennge.com/en/jobs/valid-role">Apply</a>
              <p>Some description.</p>
            </article>
            </body></html>
            """;
        List<HenngeAdapter.Candidate> candidates = adapter.parseCandidates(html, BASE_URL);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).title()).isEqualTo("Valid Role");
    }
}
