package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.AnalysisClauseRelation;
import com.team.docai.domain.analysis.entity.AnalysisClauseRelationId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisClauseRelationRepository extends JpaRepository<AnalysisClauseRelation, AnalysisClauseRelationId> {

    List<AnalysisClauseRelation> findByClauseId(Long clauseId);
}
