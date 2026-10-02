package io.github.jozephzemambo.jobradar.persistence;

import io.github.jozephzemambo.jobradar.domain.Ats;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<PostingEntity, Long> {

    /** Every posting ever seen on one board, open or closed. One query per board per ingest. */
    List<PostingEntity> findByAtsAndBoardToken(Ats ats, String boardToken);
}
