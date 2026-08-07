package dev.shoaib.jobradar.web;

import dev.shoaib.jobradar.core.CompanyRegistry;
import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import dev.shoaib.jobradar.web.dto.CompanyView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Backs {@code GET /api/companies} (Section 11). */
@RestController
public class CompanyController {

    private final CompanyRegistry companyRegistry;
    private final JobRecordRepository jobRecordRepository;

    public CompanyController(CompanyRegistry companyRegistry, JobRecordRepository jobRecordRepository) {
        this.companyRegistry = companyRegistry;
        this.jobRecordRepository = jobRecordRepository;
    }

    @GetMapping("/api/companies")
    public List<CompanyView> companies() {
        return companyRegistry.all().stream()
            .map(entry -> new CompanyView(
                entry.name(),
                entry.ats(),
                entry.relocation(),
                entry.priority(),
                jobRecordRepository.countByCompanyIgnoreCaseAndStatus(entry.name(), JobStatus.ACTIVE)))
            .toList();
    }
}
