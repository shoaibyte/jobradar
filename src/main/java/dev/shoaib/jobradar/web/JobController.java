package dev.shoaib.jobradar.web;

import dev.shoaib.jobradar.core.persistence.JobRecordEntity;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import dev.shoaib.jobradar.web.dto.JobView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Backs {@code GET /api/jobs/{id}} (Section 11). */
@RestController
public class JobController {

    private final JobRecordRepository jobRecordRepository;
    private final JsonCodec jsonCodec;

    public JobController(JobRecordRepository jobRecordRepository, JsonCodec jsonCodec) {
        this.jobRecordRepository = jobRecordRepository;
        this.jsonCodec = jsonCodec;
    }

    @GetMapping("/api/jobs/{id}")
    public JobView job(@PathVariable Integer id) {
        JobRecordEntity job = jobRecordRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job " + id + " not found"));

        return new JobView(
            job.getId(),
            job.getSource(),
            job.getExternalId(),
            job.getFingerprint(),
            job.getTitle(),
            job.getCompany(),
            job.getCity(),
            job.getCountry(),
            job.getUrl(),
            job.getDescription(),
            jsonCodec.toStringList(job.getTechTags()),
            jsonCodec.toStringList(job.getBenefits()),
            job.getSalaryRaw(),
            job.isVisaFlag(),
            job.getPostedAt(),
            job.getFirstSeen(),
            job.getLastSeen(),
            job.getStatus());
    }
}
