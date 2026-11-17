package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.ScheduleCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleCandidateRepository extends JpaRepository<ScheduleCandidate, Long> {

    List<ScheduleCandidate> findByAnalysisIdOrderByCandidateIdAsc(Long analysisId);

    long countByAnalysisId(Long analysisId);
}
