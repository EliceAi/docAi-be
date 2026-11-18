package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.AnalysisFinding;
import com.team.docai.domain.analysis.entity.FindingKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisFindingRepository extends JpaRepository<AnalysisFinding, Long> {

    long countByAnalysisIdAndKind(Long analysisId, FindingKind kind);

    List<AnalysisFinding> findByAnalysisIdOrderBySortOrderAscFindingIdAsc(Long analysisId);
}
