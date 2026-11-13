package com.team.docai.domain.document.repository;

import com.team.docai.domain.document.entity.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface DocumentCheckRepository extends JpaRepository<DocumentCheck,UUID> {
    Optional<DocumentCheck> findByIdAndUserId(UUID id,UUID userId);
    Optional<DocumentCheck> findByUserIdAndRequestKey(UUID userId,UUID requestKey);
    List<DocumentCheck> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<DocumentCheck> findByStatusIn(Collection<CheckStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from DocumentCheck d where d.id=:id and d.userId=:userId") Optional<DocumentCheck> lockOwned(UUID id,UUID userId);
}
