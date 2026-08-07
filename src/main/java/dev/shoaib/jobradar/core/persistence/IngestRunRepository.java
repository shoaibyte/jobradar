package dev.shoaib.jobradar.core.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestRunRepository extends JpaRepository<IngestRunEntity, Integer> {

    List<IngestRunEntity> findAllByOrderByIdDesc(Pageable pageable);
}
