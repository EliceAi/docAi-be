package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.AnalysisChunk;
import com.team.docai.domain.analysis.entity.ChunkStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisChunkRepository extends JpaRepository<AnalysisChunk, Long> {

    List<AnalysisChunk> findByAnalysisIdOrderByChunkNoAsc(Long analysisId);

    long countByAnalysisId(Long analysisId);

    long countByAnalysisIdAndStatus(Long analysisId, ChunkStatus status);
}
