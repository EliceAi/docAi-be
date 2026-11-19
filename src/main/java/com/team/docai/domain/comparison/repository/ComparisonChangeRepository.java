package com.team.docai.domain.comparison.repository;
import com.team.docai.domain.comparison.entity.ComparisonChange;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ComparisonChangeRepository extends JpaRepository<ComparisonChange,Long> {
 List<ComparisonChange> findByComparisonIdOrderByChangeIdAsc(Long comparisonId);
 Optional<ComparisonChange> findByChangeIdAndComparisonId(Long changeId,Long comparisonId);
}
