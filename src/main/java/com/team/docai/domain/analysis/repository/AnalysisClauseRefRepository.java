package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisClauseRefRepository extends JpaRepository<AnalysisClauseRef, Long> {

    List<AnalysisClauseRef> findByClauseIdOrderByRefIdAsc(Long clauseId);
}
