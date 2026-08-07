package dev.shoaib.jobradar.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shoaib.jobradar.core.CompanyEntry;
import dev.shoaib.jobradar.core.CompanyPriority;
import dev.shoaib.jobradar.core.CompanyRegistry;
import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.RelocationPolicy;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CompanyController.class)
class CompanyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompanyRegistry companyRegistry;

    @MockitoBean
    private JobRecordRepository jobRecordRepository;

    @Test
    void returnsCompaniesWithActiveJobCounts() throws Exception {
        CompanyEntry paypay = new CompanyEntry("PayPay", "greenhouse", "paypay", null,
            RelocationPolicy.COMPANY_WIDE, CompanyPriority.HIGH);
        CompanyEntry hennge = new CompanyEntry("HENNGE", "custom-html", null,
            "https://hennge.com/global/recruit/", RelocationPolicy.NONE, CompanyPriority.NORMAL);

        when(companyRegistry.all()).thenReturn(List.of(paypay, hennge));
        when(jobRecordRepository.countByCompanyIgnoreCaseAndStatus("PayPay", JobStatus.ACTIVE)).thenReturn(12L);
        when(jobRecordRepository.countByCompanyIgnoreCaseAndStatus("HENNGE", JobStatus.ACTIVE)).thenReturn(0L);

        mockMvc.perform(get("/api/companies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("PayPay"))
            .andExpect(jsonPath("$[0].ats").value("greenhouse"))
            .andExpect(jsonPath("$[0].relocation").value("COMPANY_WIDE"))
            .andExpect(jsonPath("$[0].priority").value("HIGH"))
            .andExpect(jsonPath("$[0].activeCount").value(12))
            .andExpect(jsonPath("$[1].name").value("HENNGE"))
            .andExpect(jsonPath("$[1].activeCount").value(0));
    }
}
