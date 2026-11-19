package com.team.docai.domain.comparison.repository;
import com.team.docai.domain.comparison.entity.Comparison;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface ComparisonRepository extends JpaRepository<Comparison,Long> {
 Optional<Comparison> findByComparisonIdAndUserId(Long comparisonId,UUID userId);
 Optional<Comparison> findByUserIdAndPreviousContractIdAndRevisedContractIdAndStatus(UUID userId,Long previousContractId,Long revisedContractId,String status);
 List<Comparison> findByPreviousContractIdAndStatus(Long previousContractId,String status);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select c from Comparison c where c.comparisonId=:id and c.userId=:userId")
 Optional<Comparison> lockOwned(@Param("userId") UUID userId,@Param("id") Long id);
}
