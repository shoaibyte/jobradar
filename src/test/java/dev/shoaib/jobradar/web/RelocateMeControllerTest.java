package dev.shoaib.jobradar.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shoaib.jobradar.core.persistence.RelocateMeIssueEntity;
import dev.shoaib.jobradar.core.persistence.RelocateMeIssueRepository;
import dev.shoaib.jobradar.core.persistence.RelocateMeJobEntity;
import dev.shoaib.jobradar.core.persistence.RelocateMeJobRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RelocateMeController.class)
@Import(JsonCodec.class)
class RelocateMeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RelocateMeIssueRepository issueRepository;

    @MockitoBean
    private RelocateMeJobRepository jobRepository;

    private static RelocateMeIssueEntity issue() {
        RelocateMeIssueEntity issue = new RelocateMeIssueEntity();
        issue.setId(7);
        issue.setWeekNumber(83);
        issue.setTitle("Weekly Hand-Curated Tech Jobs With Relocation: Week 83");
        issue.setPostDate("2026-10-01T12:06:40.505Z");
        issue.setSlug("weekly-hand-curated-tech-jobs-with-e17");
        issue.setBodyStatus(RelocateMeIssueEntity.FULL);
        issue.setJobCount(155);
        return issue;
    }

    private static RelocateMeJobEntity job() {
        RelocateMeJobEntity job = new RelocateMeJobEntity();
        job.setId(1);
        job.setIssueId(7);
        job.setSection("Back End");
        job.setPosition(1);
        job.setTitle("Senior Backend Engineer");
        job.setCompany("Secfi");
        job.setCompanyLinkedinUrl("https://www.linkedin.com/company/secfiinc/");
        job.setLocation("Amsterdam, Netherlands");
        job.setCity("Amsterdam");
        job.setCountry("Netherlands");
        job.setCountryCodes("[\"NL\"]");
        job.setKeywords("[\"Python\",\"AWS\"]");
        job.setApplyUrl("https://secfi.homerun.co/senior-back-end-engineer/en");
        job.setCanonicalUrl("https://secfi.homerun.co/senior-back-end-engineer/en");
        job.setDetails("Company: Secfi (LinkedIn)\nJob keywords: Python, AWS");
        return job;
    }

    @Test
    void searchesWithNormalisedFiltersAndAttachesIssue() throws Exception {
        when(jobRepository.search(eq(83), eq("back end"), eq(false), isNull(), eq("%\"NL\"%"), eq("nl"),
            eq("%\"python\"%"), eq("%secfi%"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(job()), PageRequest.of(0, 20), 1));
        when(issueRepository.findAllById(List.of(7))).thenReturn(List.of(issue()));

        mockMvc.perform(get("/api/relocateme/jobs").param("week", "83").param("section", "Back End")
                .param("remote", "false").param("country", "nl").param("keyword", "Python")
                .param("q", "Secfi").param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.items[0].weekNumber").value(83))
            .andExpect(jsonPath("$.items[0].title").value("Senior Backend Engineer"))
            .andExpect(jsonPath("$.items[0].applyUrl").value("https://secfi.homerun.co/senior-back-end-engineer/en"))
            .andExpect(jsonPath("$.items[0].companyLinkedinUrl").value("https://www.linkedin.com/company/secfiinc/"))
            .andExpect(jsonPath("$.items[0].countryCodes[0]").value("NL"))
            .andExpect(jsonPath("$.items[0].keywords[1]").value("AWS"))
            .andExpect(jsonPath("$.items[0].details[1]").value("Job keywords: Python, AWS"));
    }

    @Test
    void noFiltersPassesNulls() throws Exception {
        when(jobRepository.search(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mockMvc.perform(get("/api/relocateme/jobs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
        verify(jobRepository).search(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq(PageRequest.of(0, 50)));
    }

    @Test
    void countryNameResolvesToCode() throws Exception {
        when(jobRepository.search(isNull(), isNull(), isNull(), isNull(), eq("%\"NL\"%"), eq("netherlands"),
            isNull(), isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mockMvc.perform(get("/api/relocateme/jobs").param("country", "Netherlands")).andExpect(status().isOk());
        verify(jobRepository).search(isNull(), isNull(), isNull(), isNull(), eq("%\"NL\"%"), eq("netherlands"),
            isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void rejectsOversizedPage() throws Exception {
        mockMvc.perform(get("/api/relocateme/jobs").param("size", "500")).andExpect(status().isBadRequest());
    }

    @Test
    void listsIssuesWithSectionCounts() throws Exception {
        when(issueRepository.findAllByOrderByPostDateDesc()).thenReturn(List.of(issue()));
        when(jobRepository.issueSectionCounts()).thenReturn(List.of(
            new Object[] {7, "Back End", 19L}, new Object[] {7, "Front End", 4L}));

        mockMvc.perform(get("/api/relocateme/issues"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].weekNumber").value(83))
            .andExpect(jsonPath("$[0].jobCount").value(155))
            .andExpect(jsonPath("$[0].sections['Back End']").value(19));
    }

    @Test
    void unknownJobIs404() throws Exception {
        when(jobRepository.findById(99)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/relocateme/jobs/99")).andExpect(status().isNotFound());
    }
}
