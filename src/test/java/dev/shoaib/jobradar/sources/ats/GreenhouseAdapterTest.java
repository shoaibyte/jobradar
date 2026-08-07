package dev.shoaib.jobradar.sources.ats;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.shoaib.jobradar.core.CompanyEntry;
import dev.shoaib.jobradar.core.CompanyPriority;
import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.HttpFetcher;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.RelocationPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GreenhouseAdapterTest {

    private WireMockServer wireMock;
    private FetchContext ctx;

    @BeforeEach
    void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        configureFor("localhost", wireMock.port());
        HttpFetcher fetcher = new SimpleHttpFetcher();
        ctx = new FetchContext(fetcher, new NoOpCompanyRegistry(), Clock.systemUTC());
    }

    @AfterEach
    void stopServer() {
        wireMock.stop();
    }

    @Test
    void parsesJobsFromFixture() throws Exception {
        stubFor(get(urlPathEqualTo("/v1/boards/paypay/jobs"))
            .withQueryParam("content", equalTo("true"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(fixture("/fixtures/greenhouse/paypay.json"))));

        CompanyEntry entry = new CompanyEntry("PayPay", "greenhouse", "paypay", null,
            RelocationPolicy.COMPANY_WIDE, CompanyPriority.HIGH);
        GreenhouseAdapter adapter = new GreenhouseAdapter(entry, baseUrl(), true);

        assertThat(adapter.name()).isEqualTo("greenhouse:paypay");
        assertThat(adapter.enabled()).isTrue();

        List<JobPosting> postings = adapter.fetch(ctx);

        assertThat(postings).hasSize(86);
        JobPosting first = postings.get(0);
        assertThat(first.externalId()).isEqualTo("5818643004");
        assertThat(first.company()).isEqualTo("PayPay");
        assertThat(first.url()).isEqualTo("https://job-boards.greenhouse.io/paypay/jobs/5818643004");
        assertThat(first.city()).isEqualTo("Hybrid");
        assertThat(first.description()).doesNotContain("<h2>", "&lt;", "&quot;");
        assertThat(first.postedAt()).isNotNull();
    }

    @Test
    void oneJobMissingIdDoesNotFailTheWholeBatch() throws Exception {
        String malformedBody = """
            {"jobs":[
              {"id": 1, "title": "Good Job", "absolute_url": "https://x/1", "location": {"name": "Tokyo"}, "content": "hi", "departments": [], "updated_at": "2026-01-01T00:00:00-00:00"},
              {"title": "Job With No Id -- should be skipped, not crash the batch"}
            ]}
            """;
        stubFor(get(urlPathEqualTo("/v1/boards/flaky/jobs"))
            .willReturn(aResponse().withStatus(200).withBody(malformedBody)));

        CompanyEntry entry = new CompanyEntry("Flaky Co", "greenhouse", "flaky", null,
            RelocationPolicy.NONE, CompanyPriority.NORMAL);
        GreenhouseAdapter adapter = new GreenhouseAdapter(entry, baseUrl(), true);

        List<JobPosting> postings = adapter.fetch(ctx);

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).title()).isEqualTo("Good Job");
    }

    private String baseUrl() {
        return "http://localhost:" + wireMock.port() + "/v1/boards";
    }

    private static String fixture(String classpathPath) throws IOException {
        try (InputStream in = GreenhouseAdapterTest.class.getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IOException("Fixture not found: " + classpathPath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class SimpleHttpFetcher implements HttpFetcher {
        private final java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

        @Override
        public String get(String url) {
            return tryGet(url).orElseThrow(() -> new RuntimeException("404: " + url));
        }

        @Override
        public Optional<String> tryGet(String url) {
            try {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build();
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    return Optional.empty();
                }
                return Optional.of(response.body());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class NoOpCompanyRegistry implements dev.shoaib.jobradar.core.CompanyRegistry {
        @Override
        public List<CompanyEntry> all() {
            return List.of();
        }

        @Override
        public List<CompanyEntry> byAts(String ats) {
            return List.of();
        }

        @Override
        public Optional<CompanyEntry> byName(String company) {
            return Optional.empty();
        }
    }
}
