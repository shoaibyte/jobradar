package dev.shoaib.jobradar.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.persistence.JobRecordEntity;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JobController.class)
@Import(JsonCodec.class)
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobRecordRepository jobRecordRepository;

    @Test
    void returnsJobWithJsonFieldsParsedIntoLists() throws Exception {
        JobRecordEntity job = new JobRecordEntity();
        job.setId(42);
        job.setSource("greenhouse");
        job.setExternalId("gh-42");
        job.setFingerprint("fp-42");
        job.setTitle("Backend Engineer");
        job.setCompany("PayPay");
        job.setCity("Tokyo");
        job.setCountry("JP");
        job.setUrl("https://example.com/42");
        job.setDescription("Java and Go backend role");
        job.setTechTags("[\"Java\",\"Go\"]");
        job.setBenefits("[\"visa sponsorship\"]");
        job.setSalaryRaw("10M JPY");
        job.setVisaFlag(true);
        job.setPostedAt("2026-08-01");
        job.setFirstSeen("2026-08-01T00:00:00Z");
        job.setLastSeen("2026-08-05T00:00:00Z");
        job.setStatus(JobStatus.ACTIVE);

        when(jobRecordRepository.findById(42)).thenReturn(Optional.of(job));

        mockMvc.perform(get("/api/jobs/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.source").value("greenhouse"))
            .andExpect(jsonPath("$.techTags[0]").value("Java"))
            .andExpect(jsonPath("$.techTags[1]").value("Go"))
            .andExpect(jsonPath("$.benefits[0]").value("visa sponsorship"))
            .andExpect(jsonPath("$.visaFlag").value(true))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void missingJobReturns404() throws Exception {
        when(jobRecordRepository.findById(999)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/jobs/999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void nullJsonColumnsBecomeEmptyLists() throws Exception {
        JobRecordEntity job = new JobRecordEntity();
        job.setId(7);
        job.setSource("personio");
        job.setExternalId("p-7");
        job.setFingerprint("fp-7");
        job.setTitle("Java Developer");
        job.setUrl("https://example.com/7");
        job.setTechTags(null);
        job.setBenefits(null);
        job.setFirstSeen("2026-08-01T00:00:00Z");
        job.setLastSeen("2026-08-01T00:00:00Z");
        job.setStatus(JobStatus.ACTIVE);

        when(jobRecordRepository.findById(7)).thenReturn(Optional.of(job));

        mockMvc.perform(get("/api/jobs/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.techTags").isArray())
            .andExpect(jsonPath("$.techTags.length()").value(0))
            .andExpect(jsonPath("$.benefits.length()").value(0));
    }
}
