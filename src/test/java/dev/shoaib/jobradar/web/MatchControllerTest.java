package dev.shoaib.jobradar.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.MatchStrength;
import dev.shoaib.jobradar.core.persistence.JobRecordEntity;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import dev.shoaib.jobradar.core.persistence.MatchResultEntity;
import dev.shoaib.jobradar.core.persistence.MatchResultRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * JsonCodec is a plain @Component (not a controller-layer bean), so @WebMvcTest's slice
 * won't pick it up automatically -- @Import registers the real bean (backed by the
 * auto-configured ObjectMapper the slice does provide) rather than mocking JSON parsing.
 */
@WebMvcTest(MatchController.class)
@Import(JsonCodec.class)
class MatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchResultRepository matchResultRepository;

    @MockitoBean
    private JobRecordRepository jobRecordRepository;

    @Test
    void returnsMatchesJoinedWithJobRecord() throws Exception {
        MatchResultEntity match = new MatchResultEntity();
        match.setJobId(1);
        match.setScore(8.5);
        match.setStrength(MatchStrength.STRONG);
        match.setReasons("[\"java-in-title\",\"relocation-benefit\"]");
        match.setEvaluatedAt("2026-08-05T00:00:00Z");
        match.setNotified(false);

        JobRecordEntity job = new JobRecordEntity();
        job.setId(1);
        job.setTitle("Senior Java Engineer");
        job.setCompany("PayPay");
        job.setCity("Tokyo");
        job.setCountry("JP");
        job.setUrl("https://example.com/job/1");
        job.setVisaFlag(true);
        job.setSalaryRaw("8-12M JPY");
        job.setSource("greenhouse:paypay");
        job.setTechTags("[\"java\",\"kotlin\"]");
        job.setStatus(JobStatus.ACTIVE);
        job.setPostedAt("2026-08-04T09:00:00Z");
        job.setFirstSeen("2026-08-05T00:00:00Z");
        job.setLastSeen("2026-08-06T00:00:00Z");

        when(matchResultRepository.search(isNull(), isNull(), isNull())).thenReturn(List.of(match));
        when(jobRecordRepository.findAllById(List.of(1))).thenReturn(List.of(job));

        mockMvc.perform(get("/api/matches"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].jobId").value(1))
            .andExpect(jsonPath("$[0].title").value("Senior Java Engineer"))
            .andExpect(jsonPath("$[0].company").value("PayPay"))
            .andExpect(jsonPath("$[0].strength").value("STRONG"))
            .andExpect(jsonPath("$[0].score").value(8.5))
            .andExpect(jsonPath("$[0].reasons[0]").value("java-in-title"))
            .andExpect(jsonPath("$[0].reasons[1]").value("relocation-benefit"))
            .andExpect(jsonPath("$[0].visaFlag").value(true))
            .andExpect(jsonPath("$[0].salaryRaw").value("8-12M JPY"))
            .andExpect(jsonPath("$[0].source").value("greenhouse:paypay"))
            .andExpect(jsonPath("$[0].techTags[1]").value("kotlin"))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$[0].postedAt").value("2026-08-04T09:00:00Z"))
            .andExpect(jsonPath("$[0].firstSeen").value("2026-08-05T00:00:00Z"))
            .andExpect(jsonPath("$[0].lastSeen").value("2026-08-06T00:00:00Z"));
    }

    @Test
    void defaultsToNewestCollectedFirstWithScoreAsTiebreak() throws Exception {
        // Repository order is score desc; the default sort must override it.
        givenMatches(
            row(1, 9.0, "2026-08-01T10:00:00Z", null),
            row(2, 4.0, "2026-10-01T06:48:34.4188Z", null),
            // 10 microseconds after #2, but "...4188Z" > "...41881Z" as text, so a string
            // comparison would wrongly put #2 first. Pins Instant-based ordering.
            row(3, 5.0, "2026-10-01T06:48:34.41881Z", null),
            row(4, 7.0, "2026-10-01T06:48:34.41881Z", null));

        mockMvc.perform(get("/api/matches"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].jobId").value(org.hamcrest.Matchers.contains(4, 3, 2, 1)));
    }

    @Test
    void sortByScoreKeepsScoreOrder() throws Exception {
        givenMatches(
            row(1, 9.0, "2026-08-01T10:00:00Z", null),
            row(2, 4.0, "2026-10-01T00:00:00Z", null));

        mockMvc.perform(get("/api/matches").param("sort", "score"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].jobId").value(org.hamcrest.Matchers.contains(1, 2)));
    }

    @Test
    void sortByPostedPutsMissingPostedDateLast() throws Exception {
        givenMatches(
            row(1, 9.0, "2026-10-01T00:00:00Z", null),
            row(2, 4.0, "2026-08-01T00:00:00Z", "2026-07-30T00:00:00Z"),
            row(3, 5.0, "2026-08-01T00:00:00Z", "2026-07-31T00:00:00Z"));

        mockMvc.perform(get("/api/matches").param("sort", "POSTED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].jobId").value(org.hamcrest.Matchers.contains(3, 2, 1)));
    }

    @Test
    void invalidSortReturns400() throws Exception {
        mockMvc.perform(get("/api/matches").param("sort", "nonsense"))
            .andExpect(status().isBadRequest());
    }

    private record Row(MatchResultEntity match, JobRecordEntity job) {}

    private static Row row(int id, double score, String firstSeen, String postedAt) {
        MatchResultEntity match = new MatchResultEntity();
        match.setJobId(id);
        match.setScore(score);
        match.setStrength(MatchStrength.MATCH);
        match.setReasons("[]");
        JobRecordEntity job = new JobRecordEntity();
        job.setId(id);
        job.setTitle("Job " + id);
        job.setUrl("https://example.com/job/" + id);
        job.setFirstSeen(firstSeen);
        job.setPostedAt(postedAt);
        return new Row(match, job);
    }

    /** Stubs the repository to return the rows in score-desc order, as the real query does. */
    private void givenMatches(Row... rows) {
        List<Row> byScore = java.util.Arrays.stream(rows)
            .sorted(java.util.Comparator.comparingDouble((Row r) -> r.match().getScore()).reversed())
            .toList();
        when(matchResultRepository.search(isNull(), isNull(), isNull()))
            .thenReturn(byScore.stream().map(Row::match).toList());
        when(jobRecordRepository.findAllById(byScore.stream().map(r -> r.match().getJobId()).toList()))
            .thenReturn(byScore.stream().map(Row::job).toList());
    }

    @Test
    void parsesFilterParamsCaseInsensitivelyAndForwardsToRepository() throws Exception {
        when(matchResultRepository.search(eq(MatchStrength.MATCH), eq(5.0), eq(JobStatus.ACTIVE)))
            .thenReturn(List.of());

        mockMvc.perform(get("/api/matches").param("strength", "match")
                .param("minScore", "5")
                .param("status", "active"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void skipsMatchWhenJobRecordMissing() throws Exception {
        MatchResultEntity match = new MatchResultEntity();
        match.setJobId(404);
        match.setScore(5.0);
        match.setStrength(MatchStrength.MATCH);
        match.setReasons("[]");

        when(matchResultRepository.search(isNull(), isNull(), isNull())).thenReturn(List.of(match));
        when(jobRecordRepository.findAllById(List.of(404))).thenReturn(List.of());

        mockMvc.perform(get("/api/matches"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invalidStrengthReturns400() throws Exception {
        mockMvc.perform(get("/api/matches").param("strength", "nonsense"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void invalidStatusReturns400() throws Exception {
        mockMvc.perform(get("/api/matches").param("status", "nonsense"))
            .andExpect(status().isBadRequest());
    }
}
