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
import java.util.Optional;
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

        when(matchResultRepository.search(isNull(), isNull(), isNull())).thenReturn(List.of(match));
        when(jobRecordRepository.findById(1)).thenReturn(Optional.of(job));

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
            .andExpect(jsonPath("$[0].salaryRaw").value("8-12M JPY"));
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
        when(jobRecordRepository.findById(404)).thenReturn(Optional.empty());

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
