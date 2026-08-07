package dev.shoaib.jobradar.sources.ats;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
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

class PersonioAdapterTest {

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
    void parsesPopulatedFixtureOverHttp() throws Exception {
        stubFor(get(urlPathEqualTo("/xml"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                .withBody(fixture("/fixtures/personio/verimi.xml"))));

        CompanyEntry entry = new CompanyEntry("Verimi", "personio", "verimi", null,
            RelocationPolicy.NONE, CompanyPriority.NORMAL);
        PersonioAdapter adapter = new PersonioAdapter(entry, "http://localhost:" + wireMock.port() + "/xml?language=en", true);

        assertThat(adapter.name()).isEqualTo("personio:verimi");

        List<JobPosting> postings = adapter.fetch(ctx);

        assertThat(postings).hasSize(2);
        JobPosting first = postings.get(0);
        assertThat(first.externalId()).isEqualTo("1894273");
        assertThat(first.title()).contains("Senior Software Engineer");
        assertThat(first.company()).isEqualTo("Verimi");
        assertThat(first.city()).isEqualTo("Berlin");
        assertThat(first.url()).isEqualTo("https://verimi.jobs.personio.de/job/1894273?language=en");
        assertThat(first.description()).contains("Java 17 and Spring Boot").doesNotContain("<b>", "&lt;");
        assertThat(first.techTags()).contains("Java", "Spring Boot", "Kotlin");
        assertThat(first.postedAt()).isNotNull();
    }

    @Test
    void journiFixtureParsesRelocationSupportIntoDescription() {
        CompanyEntry entry = new CompanyEntry("Journi", "personio", "journi-gmbh", null,
            RelocationPolicy.NONE, CompanyPriority.HIGH);
        PersonioAdapter adapter = new PersonioAdapter(entry, "unused", true);

        List<JobPosting> postings = adapter.parse(readFixture("/fixtures/personio/journi-gmbh.xml"));

        assertThat(postings).hasSize(1);
        JobPosting posting = postings.get(0);
        assertThat(posting.description()).contains("visa sponsorship", "relocation support");
        assertThat(posting.techTags()).contains("Kotlin", "Scala", "Java");
    }

    @Test
    void emptyFeedProducesEmptyListWithoutThrowing() {
        CompanyEntry entry = new CompanyEntry("Verimi", "personio", "verimi", null,
            RelocationPolicy.NONE, CompanyPriority.NORMAL);
        PersonioAdapter adapter = new PersonioAdapter(entry, "unused", true);

        List<JobPosting> postings = adapter.parse(readFixture("/fixtures/personio/verimi.empty.xml"));

        assertThat(postings).isEmpty();
    }

    @Test
    void journiEmptyFeedProducesEmptyListWithoutThrowing() {
        CompanyEntry entry = new CompanyEntry("Journi", "personio", "journi-gmbh", null,
            RelocationPolicy.NONE, CompanyPriority.HIGH);
        PersonioAdapter adapter = new PersonioAdapter(entry, "unused", true);

        List<JobPosting> postings = adapter.parse(readFixture("/fixtures/personio/journi-gmbh.empty.xml"));

        assertThat(postings).isEmpty();
    }

    @Test
    void positionMissingIdIsSkippedWithoutFailingTheBatch() {
        CompanyEntry entry = new CompanyEntry("Test Co", "personio", "test", null,
            RelocationPolicy.NONE, CompanyPriority.NORMAL);
        PersonioAdapter adapter = new PersonioAdapter(entry, "unused", true);

        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <workzag-jobs>
              <position>
                <id>111</id>
                <name>Good Position</name>
                <office>Berlin</office>
              </position>
              <position>
                <name>Missing Id -- should be skipped</name>
              </position>
            </workzag-jobs>
            """;

        List<JobPosting> postings = adapter.parse(xml);

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).title()).isEqualTo("Good Position");
    }

    private static String readFixture(String classpathPath) {
        try {
            return fixture(classpathPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String fixture(String classpathPath) throws IOException {
        try (InputStream in = PersonioAdapterTest.class.getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IOException("Fixture not found: " + classpathPath);
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
