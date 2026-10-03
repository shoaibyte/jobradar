package dev.shoaib.jobradar.core.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelocateMeIssueRepository extends JpaRepository<RelocateMeIssueEntity, Integer> {

    Optional<RelocateMeIssueEntity> findBySlug(String slug);

    List<RelocateMeIssueEntity> findAllByOrderByPostDateDesc();
}
