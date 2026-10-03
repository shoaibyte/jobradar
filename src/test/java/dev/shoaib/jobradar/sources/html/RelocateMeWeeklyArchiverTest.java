package dev.shoaib.jobradar.sources.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import dev.shoaib.jobradar.core.HttpFetcher;
import dev.shoaib.jobradar.core.persistence.RelocateMeIssueEntity;
import dev.shoaib.jobradar.core.persistence.RelocateMeIssueRepository;
import dev.shoaib.jobradar.core.persistence.RelocateMeJobEntity;
import dev.shoaib.jobradar.core.persistence.RelocateMeJobRepository;
import dev.shoaib.jobradar.pipeline.HttpAuthProperties;
import dev.shoaib.jobradar.sources.html.RelocateMeSubstackAdapter.IssueEntries;
import dev.shoaib.jobradar.sources.html.RelocateMeSubstackAdapter.SectionEntry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

class RelocateMeWeeklyArchiverTest {

    private static final String BASE = "https://relocateme.substack.com";

    private static final RelocateMeSubstackProperties PROPS = new RelocateMeSubstackProperties(
        true, BASE, 20, 6, 45,
        List.of("weekly-hand-curated-tech-jobs"), List.of("Relocation-friendly jobs"),
        List.of("Back End", "Full Stack"), "");

    private static final RelocateMeArchiveProperties ARCHIVE_PROPS = new RelocateMeArchiveProperties(
        "(?i)^weekly hand-curated tech jobs with relocation", "-");

    private final HttpFetcher http = mock(HttpFetcher.class);
    private final RelocateMeIssueRepository issues = mock(RelocateMeIssueRepository.class);
    private final RelocateMeJobRepository jobs = mock(RelocateMeJobRepository.class);
    private final RelocateMeSubstackAdapter adapter =
        new RelocateMeSubstackAdapter(PROPS, new HttpAuthProperties(Map.of()));
    private final RelocateMeWeeklyArchiver archiver = new RelocateMeWeeklyArchiver(
        adapter, PROPS, ARCHIVE_PROPS, http, issues, jobs, mock(PlatformTransactionManager.class));

    private static String fixture(String path) {
        try (InputStream in = RelocateMeWeeklyArchiverTest.class.getClassLoader()
                .getResourceAsStream("fixtures/relocateme-substack/" + path)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void stubs() {
        when(issues.save(any(RelocateMeIssueEntity.class))).thenAnswer(inv -> {
            RelocateMeIssueEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(e.getSlug().hashCode());
            }
            return e;
        });
        when(issues.findBySlug(anyString())).thenReturn(Optional.empty());
        when(http.tryGet(contains("/api/v1/archive?sort=new&offset=0&"))).thenReturn(Optional.of(fixture("archive-page1.json")));
        when(http.tryGet(contains("/api/v1/archive?sort=new&offset=4&"))).thenReturn(Optional.of("[]"));
    }

    @Test
    void parsesEveryEntryAcrossAllSectionsInDocumentOrder() throws Exception {
        IssueEntries parsed = adapter.parseAllEntries(fixture("post-weekly-fd0.json"));

        assertThat(parsed.entries()).extracting(SectionEntry::section)
            .containsExactly("Back End", "Back End", "Back End", "Full Stack", "Front End");
        assertThat(parsed.entries()).extracting(SectionEntry::position).containsExactly(1, 2, 3, 1, 1);
        SectionEntry adyen = parsed.entries().get(1);
        assertThat(adyen.entry().title()).isEqualTo("Senior Java Engineer");
        assertThat(adyen.entry().company()).isEqualTo("Adyen");
        assertThat(adyen.entry().location()).isEqualTo("Amsterdam, Netherlands");
        assertThat(adyen.entry().industrySize()).isEqualTo("FinTech | 1K–5K employees");
        assertThat(adyen.entry().keywords()).containsExactly("Java", "Spring Boot", "PostgreSQL", "Kafka");
        assertThat(adyen.entry().visaLines()).hasSize(1);
        assertThat(parsed.advertisedCounts()).containsEntry("front end", 1);
    }

    @Test
    void readsLocationFromTitleLineAndDetailsFromSiblingParagraphs() throws Exception {
        IssueEntries parsed = adapter.parseAllEntries(fixture("post-title-line-location.json"));

        assertThat(parsed.subtitle()).isEqualTo("3 handpicked jobs");
        assertThat(parsed.entries()).extracting(SectionEntry::section)
            .containsExactly("Back End", "Back End", "Back End", "Back End", "Mobile");
        RelocateMeSubstackAdapter.Entry secfi = parsed.entries().get(0).entry();
        assertThat(secfi.location()).isEqualTo("Amsterdam, Netherlands");
        assertThat(secfi.city()).isEqualTo("Amsterdam");
        assertThat(secfi.country()).isEqualTo("Netherlands");
        assertThat(secfi.where().countryCodes()).containsExactly("NL");
        assertThat(secfi.companyLinkedinUrl()).isEqualTo("https://www.linkedin.com/company/secfiinc/");

        RelocateMeSubstackAdapter.Entry alpaca = parsed.entries().get(2).entry();
        assertThat(alpaca.title()).isEqualTo("Senior DevOps Engineer");
        assertThat(alpaca.where().remote()).isTrue();
        assertThat(alpaca.where().remoteRegion()).isEqualTo("EMEA, LATAM");
        assertThat(alpaca.location()).isNull();
        assertThat(alpaca.country()).isNull();

        RelocateMeSubstackAdapter.Entry hud = parsed.entries().get(3).entry();
        assertThat(hud.location()).isEqualTo("Singapore or San Francisco, USA");
        assertThat(hud.where().countryCodes()).containsExactly("SG", "US");
        assertThat(hud.country()).isNull();
        assertThat(hud.companyLinkedinUrl()).isNull();

        RelocateMeSubstackAdapter.Entry acme = parsed.entries().get(1).entry();
        assertThat(acme.location()).isEqualTo("Cambridge or Manchester, UK");
        assertThat(acme.city()).isEqualTo("Cambridge or Manchester");
        assertThat(acme.country()).isEqualTo("UK");
        assertThat(acme.company()).isEqualTo("Acme");
        assertThat(acme.keywords()).containsExactly("Go", "Kubernetes");

        assertThat(parsed.entries().get(4).entry().location()).isEqualTo("Remote");
        assertThat(parsed.entries().get(4).entry().where().remote()).isTrue();
        assertThat(RelocateMeWeeklyArchiver.bodyStatus(parsed, "only_paid")).isEqualTo(RelocateMeIssueEntity.FULL);
    }

    @Test
    void discoversOnlyWeeklyIssuesAcrossTheWholeArchive() {
        assertThat(archiver.discoverWeeklyIssues()).extracting(RelocateMeSubstackAdapter.PostRef::slug)
            .containsExactly("weekly-hand-curated-tech-jobs-with-fd0", "weekly-hand-curated-tech-jobs-with-347",
                "weekly-hand-curated-tech-jobs-with-034");
    }

    @Test
    void storesFullIssuesWithAllRowsAndPreviewsWithNone() {
        when(http.tryGet(BASE + "/api/v1/posts/weekly-hand-curated-tech-jobs-with-fd0"))
            .thenReturn(Optional.of(fixture("post-weekly-fd0.json")));
        when(http.tryGet(BASE + "/api/v1/posts/weekly-hand-curated-tech-jobs-with-347"))
            .thenReturn(Optional.of(fixture("post-weekly-347.json")));
        when(http.tryGet(BASE + "/api/v1/posts/weekly-hand-curated-tech-jobs-with-034"))
            .thenReturn(Optional.of(fixture("post-paywalled-preview.json")));

        RelocateMeWeeklyArchiver.Summary summary = archiver.archive();

        assertThat(summary).isEqualTo(new RelocateMeWeeklyArchiver.Summary(3, 2, 1, 0, 0, 0));
        ArgumentCaptor<RelocateMeIssueEntity> saved = ArgumentCaptor.forClass(RelocateMeIssueEntity.class);
        verify(issues, org.mockito.Mockito.times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(RelocateMeIssueEntity::getWeekNumber).containsExactly(78, 77, 44);
        assertThat(saved.getAllValues()).extracting(RelocateMeIssueEntity::getJobCount).containsExactly(5, 2, 0);
        assertThat(saved.getAllValues().get(2).getBodyStatus()).isEqualTo(RelocateMeIssueEntity.PREVIEW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RelocateMeJobEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(jobs, org.mockito.Mockito.times(3)).saveAll(rows.capture());
        RelocateMeJobEntity first = rows.getAllValues().get(0).get(0);
        assertThat(first.getCanonicalUrl()).isEqualTo("https://jobs.ashbyhq.com/trust-wallet/f368ed72");
        assertThat(first.getKeywords()).startsWith("[\"Go\"");
    }

    @Test
    void skipsFullIssuesAndAnonymousPreviews() {
        RelocateMeIssueEntity full = new RelocateMeIssueEntity();
        full.setBodyStatus(RelocateMeIssueEntity.FULL);
        RelocateMeIssueEntity preview = new RelocateMeIssueEntity();
        preview.setBodyStatus(RelocateMeIssueEntity.PREVIEW);
        when(issues.findBySlug("weekly-hand-curated-tech-jobs-with-fd0")).thenReturn(Optional.of(full));
        when(issues.findBySlug("weekly-hand-curated-tech-jobs-with-347")).thenReturn(Optional.of(preview));
        when(issues.findBySlug("weekly-hand-curated-tech-jobs-with-034")).thenReturn(Optional.of(full));

        RelocateMeWeeklyArchiver.Summary summary = archiver.archive();

        assertThat(summary.skipped()).isEqualTo(3);
        verify(http, never()).tryGet(contains("/api/v1/posts/"));
    }

    @Test
    void paidSneakPeekWithoutAdvertisedSectionsIsAPreview() {
        RelocateMeSubstackAdapter.Entry job = new RelocateMeSubstackAdapter.Entry(
            "Backend Engineer", "https://example.com/job", "Acme", null, JobLocation.NONE, null,
            List.of(), List.of(), List.of());
        IssueEntries sneakPeek = new IssueEntries("only_paid", "",
            List.of(new SectionEntry("Here are 10 jobs from this week’s list to give you a sneak peek:", 1, job)),
            Map.of("back end", 33), List.of());
        IssueEntries full = new IssueEntries("only_paid", "",
            List.of(new SectionEntry("Back End", 1, job)), Map.of("back end", 33), List.of());

        assertThat(RelocateMeWeeklyArchiver.bodyStatus(sneakPeek, "only_paid")).isEqualTo(RelocateMeIssueEntity.PREVIEW);
        assertThat(RelocateMeWeeklyArchiver.bodyStatus(full, "only_paid")).isEqualTo(RelocateMeIssueEntity.FULL);
    }

    @Test
    void readsEmbeddedDatawrapperTables() {
        String body = "<h3>Back End</h3><div class=\"datawrapper-wrap outer\">"
            + "<iframe src=\"https://datawrapper.dwcdn.net/jnXsV/1/\"></iframe></div>"
            + "<h3><strong>Front End</strong></h3><div class=\"datawrapper-wrap outer\">"
            + "<iframe src=\"https://datawrapper.dwcdn.net/zzzzz/1/\"></iframe></div>";
        String postJson = "{\"audience\":\"only_paid\",\"body_html\":\""
            + new String(JsonStringEncoder.getInstance().quoteAsString(body)) + "\"}";
        String csv = "Role,Location,Company,Size,Industry,LinkedIn page,Job keywords\n"
            + "[Backend Developer](https://jobs.ashbyhq.com/corti/fdf856ad?departmentId=c4) ✅,\"Copenhagen, Denmark\","
            + "Corti,51-200,AI/Healthcare,[LINK](https://www.linkedin.com/company/corti/),\"Golang, REST APIs\"\n"
            + "No link here,Berlin,Acme,,,,\n";
        when(http.tryGet(contains("/api/v1/archive?sort=new&offset=0&"))).thenReturn(Optional.of(
            "[{\"slug\":\"hand-curated-tech-jobs-with-relocation\",\"title\":\"Weekly Hand-Curated Tech Jobs With "
                + "Relocation: Week 1\",\"post_date\":\"2025-02-26T10:00:00Z\",\"audience\":\"only_paid\"}]"));
        when(http.tryGet(contains("/api/v1/archive?sort=new&offset=1&"))).thenReturn(Optional.of("[]"));
        when(http.tryGet(BASE + "/api/v1/posts/hand-curated-tech-jobs-with-relocation")).thenReturn(Optional.of(postJson));
        when(http.tryGet("https://datawrapper.dwcdn.net/jnXsV/1/dataset.csv")).thenReturn(Optional.of(csv));
        when(http.tryGet("https://datawrapper.dwcdn.net/zzzzz/1/dataset.csv")).thenReturn(Optional.empty());

        RelocateMeWeeklyArchiver.Summary summary = archiver.archive();

        assertThat(summary.failed()).isEqualTo(1);
        ArgumentCaptor<RelocateMeIssueEntity> saved = ArgumentCaptor.forClass(RelocateMeIssueEntity.class);
        verify(issues).save(saved.capture());
        assertThat(saved.getValue().getBodyStatus()).isEqualTo(RelocateMeIssueEntity.PARTIAL);
        assertThat(saved.getValue().getJobCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RelocateMeJobEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(jobs).saveAll(rows.capture());
        RelocateMeJobEntity corti = rows.getValue().get(0);
        assertThat(corti.getSection()).isEqualTo("Back End");
        assertThat(corti.getTitle()).isEqualTo("Backend Developer");
        assertThat(corti.getApplyUrl()).isEqualTo("https://jobs.ashbyhq.com/corti/fdf856ad?departmentId=c4");
        assertThat(corti.getCity()).isEqualTo("Copenhagen");
        assertThat(corti.getCountry()).isEqualTo("Denmark");
        assertThat(corti.getIndustrySize()).isEqualTo("AI/Healthcare | 51-200");
        assertThat(corti.getKeywords()).isEqualTo("[\"Golang\",\"REST APIs\"]");
        assertThat(corti.getCountryCodes()).isEqualTo("[\"DK\"]");
        assertThat(corti.getCompanyLinkedinUrl()).isEqualTo("https://www.linkedin.com/company/corti/");
        assertThat(corti.isRemote()).isFalse();
    }

    @Test
    void parsesWeekNumberFromTitle() {
        assertThat(RelocateMeWeeklyArchiver.weekNumber("Weekly Hand-Curated Tech Jobs With Relocation: Week 83"))
            .isEqualTo(83);
        assertThat(RelocateMeWeeklyArchiver.weekNumber("Internal Mobility as an Alternative")).isNull();
    }
}
