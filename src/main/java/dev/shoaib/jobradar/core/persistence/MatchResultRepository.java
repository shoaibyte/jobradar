package dev.shoaib.jobradar.core.persistence;

import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.MatchStrength;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchResultRepository extends JpaRepository<MatchResultEntity, Integer> {

    /** jobId IS the primary key — use {@link #findById} to look up a single match. */

    @Query("select m from MatchResultEntity m, JobRecordEntity j "
        + "where j.id = m.jobId and j.status = :status and m.notified = false "
        + "order by m.score desc")
    List<MatchResultEntity> findUnnotifiedByJobStatus(@Param("status") JobStatus status);

    /** Filter-friendly search backing GET /api/matches; any parameter may be null to skip that filter. */
    @Query("select m from MatchResultEntity m, JobRecordEntity j "
        + "where j.id = m.jobId "
        + "and (:strength is null or m.strength = :strength) "
        + "and (:minScore is null or m.score >= :minScore) "
        + "and (:status is null or j.status = :status) "
        + "order by m.score desc")
    List<MatchResultEntity> search(@Param("strength") MatchStrength strength,
        @Param("minScore") Double minScore, @Param("status") JobStatus status);
}
