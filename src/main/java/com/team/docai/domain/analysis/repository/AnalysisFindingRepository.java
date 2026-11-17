package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.AnalysisFinding;
import com.team.docai.domain.analysis.entity.FindingKind;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisFindingRepository extends JpaRepository<AnalysisFinding, Long> {

    long countByAnalysisIdAndKind(Long analysisId, FindingKind kind);
}
