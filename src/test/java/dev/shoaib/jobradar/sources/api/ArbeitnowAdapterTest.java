package dev.shoaib.jobradar.sources.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shoaib.jobradar.core.CompanyEntry;
import dev.shoaib.jobradar.core.CompanyRegistry;
import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.HttpFetcher;
import dev.shoaib.jobradar.core.JobPosting;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArbeitnowAdapterTest {

    private static final String FIXTURE_URL = "https://www.arbeitnow.com/api/job-board-api?visa_sponsorship=true&page=1";

    @Test
    void parsePageExtractsFieldsFromRealFixture() throws Exception {
        ArbeitnowAdapter adapter = new ArbeitnowAdapter("https://www.arbeitnow.com/api/job-board-api", true, 5);

        ArbeitnowAdapter.Page page = adapter.parsePage(fixture());

        assertThat(page.jobs()).hasSize(175);
        assertThat(page.nextLink()).isEqualTo("https://www.arbeitnow.com/api/job-board-api?visa_sponsorship=true&page=2");
    }

    @Test
    void everyPostingIsVisaFlaggedBecauseTheQueryAlreadyFiltersOnIt() throws Exception {
        ArbeitnowAdapter adapter = new ArbeitnowAdapter("https://www.arbeitnow.com/api/job-board-api", true, 1);
        StubHttpFetcher http = new StubHttpFetcher(Map.of(FIXTURE_URL, fixture()));
        FetchContext ctx = new FetchContext(http, new NoOpCompanyRegistry(), Clock.systemUTC());

        List<JobPosting> postings = adapter.fetch(ctx);

        assertThat(postings).isNotEmpty();
        assertThat(postings).allMatch(JobPosting::visaFlag);
    }

    @Test
    void fieldExtractionMatchesFirstFixtureJob() throws Exception {
        ArbeitnowAdapter adapter = new ArbeitnowAdapter("https://www.arbeitnow.com/api/job-board-api", true, 1);
        StubHttpFetcher http = new StubHttpFetcher(Map.of(FIXTURE_URL, fixture()));
        FetchContext ctx = new FetchContext(http, new NoOpCompanyRegistry(), Clock.systemUTC());

        List<JobPosting> postings = adapter.fetch(ctx);
        JobPosting first = postings.get(0);

        assertThat(first.externalId()).isEqualTo("technical-founders-associate-berlin-88439");
        assertThat(first.company()).isEqualTo("Lyceum");
        assertThat(first.title()).isEqualTo("Technical Founders Associate");
        assertThat(first.city()).isEqualTo("Berlin");
        assertThat(first.country()).isEqualTo("DE");
        assertThat(first.description()).doesNotContain("<h2>", "<strong>", "<p");
        assertThat(first.techTags()).contains("Product", "Full Time");
        assertThat(first.url()).isEqualTo(
            "https://www.arbeitnow.com/jobs/companies/lyceum/technical-founders-associate-berlin-88439");
        assertThat(first.postedAt()).isNotNull();
    }

    @Test
    void stopsAtMaxPagesEvenIfLinksNextKeepsGoing() throws Exception {
        ArbeitnowAdapter adapter = new ArbeitnowAdapter("http://x/api", true, 2);
        String page1 = """
            {"data":[{"slug":"a","company_name":"A","title":"A","description":"","remote":false,"url":"u","tags":[],"job_types":[],"location":"Berlin","created_at":1700000000}],
             "links":{"next":"http://x/api?page=2"}}
            """;
        String page2 = """
            {"data":[{"slug":"b","company_name":"B","title":"B","description":"","remote":false,"url":"u","tags":[],"job_types":[],"location":"Berlin","created_at":1700000000}],
             "links":{"next":"http://x/api?page=3"}}
            """;
        String page3 = """
            {"data":[{"slug":"c","company_name":"C","title":"C","description":"","remote":false,"url":"u","tags":[],"job_types":[],"location":"Berlin","created_at":1700000000}],
             "links":{"next":null}}
            """;
        StubHttpFetcher http = new StubHttpFetcher(Map.of(
            "http://x/api?visa_sponsorship=true&page=1", page1,
            "http://x/api?page=2", page2,
            "http://x/api?page=3", page3));
        FetchContext ctx = new FetchContext(http, new NoOpCompanyRegistry(), Clock.systemUTC());

        List<JobPosting> postings = adapter.fetch(ctx);

        assertThat(postings).extracting(JobPosting::externalId).containsExactly("a", "b"); // page 3 never fetched
    }

    @Test
    void stopsWhenLinksNextIsNull() throws Exception {
        ArbeitnowAdapter adapter = new ArbeitnowAdapter("http://x/api", true, 5);
        String onlyPage = """
            {"data":[{"slug":"a","company_name":"A","title":"A","description":"","remote":true,"url":"u","tags":[],"job_types":[],"location":"","created_at":1700000000}],
             "links":{"next":null}}
            """;
        StubHttpFetcher http = new StubHttpFetcher(Map.of("http://x/api?visa_sponsorship=true&page=1", onlyPage));
        FetchContext ctx = new FetchContext(http, new NoOpCompanyRegistry(), Clock.systemUTC());

        List<JobPosting> postings = adapter.fetch(ctx);

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).city()).isEqualTo("Remote");
    }

    @Test
    void jobMissingSlugIsSkippedWithoutFailingTheWholeBatch() throws Exception {
        ArbeitnowAdapter adapter = new ArbeitnowAdapter("http://x/api", true, 1);
        String page = """
            {"data":[
              {"slug":"good","company_name":"Good Co","title":"Good","description":"","remote":false,"url":"u","tags":[],"job_types":[],"location":"Berlin","created_at":1700000000},
              {"company_name":"Bad Co -- missing slug, should be skipped"}
            ],
             "links":{"next":null}}
            """;
        StubHttpFetcher http = new StubHttpFetcher(Map.of("http://x/api?visa_sponsorship=true&page=1", page));
        FetchContext ctx = new FetchContext(http, new NoOpCompanyRegistry(), Clock.systemUTC());

        List<JobPosting> postings = adapter.fetch(ctx);

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).company()).isEqualTo("Good Co");
    }

    private static String fixture() throws IOException {
        try (InputStream in = ArbeitnowAdapterTest.class.getResourceAsStream("/fixtures/arbeitnow/page1.json")) {
            if (in == null) {
                throw new IOException("fixture not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class StubHttpFetcher implements HttpFetcher {
        private final Map<String, String> responses;

        StubHttpFetcher(Map<String, String> responses) {
            this.responses = new HashMap<>(responses);
        }

        @Override
        public String get(String url) {
            String body = responses.get(url);
            if (body == null) {
                throw new RuntimeException("Unstubbed URL: " + url);
            }
            return body;
        }

        @Override
        public Optional<String> tryGet(String url) {
            return Optional.ofNullable(responses.get(url));
        }
    }

    private static final class NoOpCompanyRegistry implements CompanyRegistry {
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
