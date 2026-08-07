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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeverAdapterTest {

    private WireMockServer wireMock;
    private FetchContext ctx;

    @BeforeEach
    void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        configureFor("localhost", wireMock.port());
        ctx = new FetchContext(new SimpleHttpFetcher(), new NoOpCompanyRegistry(), Clock.systemUTC());
    }

    @AfterEach
    void stopServer() {
        wireMock.stop();
    }

    @Test
    void parsesFixtureOverHttp() throws Exception {
        stubFor(get(urlPathEqualTo("/example"))
            .withQueryParam("mode", equalTo("json"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(fixture())));

        CompanyEntry entry = new CompanyEntry("Example Co", "lever", "example", null,
            RelocationPolicy.COMPANY_WIDE, CompanyPriority.NORMAL);
        LeverAdapter adapter = new LeverAdapter(entry, "http://localhost:" + wireMock.port(), true);

        assertThat(adapter.name()).isEqualTo("lever:example");

        List<JobPosting> postings = adapter.fetch(ctx);

        assertThat(postings).hasSize(2);
        JobPosting backend = postings.get(0);
        assertThat(backend.externalId()).isEqualTo("8f3a2c1e-9b4d-4e2a-8c6f-1a2b3c4d5e6f");
        assertThat(backend.title()).isEqualTo("Backend Engineer, Java (Relocation Available)");
        assertThat(backend.city()).isEqualTo("Amsterdam, Netherlands");
        assertThat(backend.url()).isEqualTo("https://jobs.lever.co/example/8f3a2c1e-9b4d-4e2a-8c6f-1a2b3c4d5e6f");
        assertThat(backend.description()).contains("Go experience is a plus", "Requirements:", "Benefits:");
        assertThat(backend.postedAt()).isNotNull();
    }

    @Test
    void postingMissingIdIsSkippedWithoutFailingTheBatch() throws Exception {
        CompanyEntry entry = new CompanyEntry("Test Co", "lever", "test", null,
            RelocationPolicy.NONE, CompanyPriority.NORMAL);
        LeverAdapter adapter = new LeverAdapter(entry, "http://unused", true);

        String json = """
            [
              {"id": "good-1", "text": "Good Posting", "categories": {}, "lists": [], "hostedUrl": "https://x/good-1", "createdAt": 1700000000000},
              {"text": "Missing Id -- should be skipped"}
            ]
            """;

        List<JobPosting> postings = adapter.parse(json);

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).title()).isEqualTo("Good Posting");
    }

    private static String fixture() throws IOException {
        try (InputStream in = LeverAdapterTest.class.getResourceAsStream("/fixtures/lever/sample-postings.json")) {
            if (in == null) {
                throw new IOException("fixture not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class SimpleHttpFetcher implements HttpFetcher {
        private final HttpClient client = HttpClient.newHttpClient();

        @Override
        public String get(String url) {
            return tryGet(url).orElseThrow(() -> new RuntimeException("404: " + url));
        }

        @Override
        public Optional<String> tryGet(String url) {
            try {
                var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
