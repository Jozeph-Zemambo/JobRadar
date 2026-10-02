package io.github.jozephzemambo.jobradar.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestRunRepository extends JpaRepository<IngestRunEntity, Long> {

    Optional<IngestRunEntity> findFirstByOrderByStartedAtDesc();

    Optional<IngestRunEntity> findFirstByOrderByStartedAtAsc();
}
