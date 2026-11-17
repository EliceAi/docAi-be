package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisKeyTermRepository extends JpaRepository<AnalysisKeyTerm, Long> {

    List<AnalysisKeyTerm> findByAnalysisIdOrderBySortOrderAsc(Long analysisId);
}
