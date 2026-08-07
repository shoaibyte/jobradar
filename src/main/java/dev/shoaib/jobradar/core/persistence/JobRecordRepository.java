package dev.shoaib.jobradar.core.persistence;

import dev.shoaib.jobradar.core.JobStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRecordRepository extends JpaRepository<JobRecordEntity, Integer> {

    Optional<JobRecordEntity> findBySourceAndExternalId(String source, String externalId);

    List<JobRecordEntity> findBySourceAndStatus(String source, JobStatus status);

    List<JobRecordEntity> findByFingerprint(String fingerprint);

    long countByCompanyIgnoreCaseAndStatus(String company, JobStatus status);
}
