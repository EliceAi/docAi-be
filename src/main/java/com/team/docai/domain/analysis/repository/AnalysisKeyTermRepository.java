package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisKeyTermRepository extends JpaRepository<AnalysisKeyTerm, Long> {

    List<AnalysisKeyTerm> findByAnalysisIdOrderBySortOrderAsc(Long analysisId);

    /** (analysis_id, label) 은 UNIQUE (uq_key_term) 라 하나뿐이다 */
    Optional<AnalysisKeyTerm> findByAnalysisIdAndLabel(Long analysisId, String label);
}
