package com.team.docai.domain.analysis.dto;

import com.team.docai.domain.analysis.entity.Analysis;

import java.time.OffsetDateTime;

/**
 * A2 AnalysisService.getVersionState 의 반환. 버전 상태 판단(분석 중/완료/실패/DRAFT)은 B 가 한다.
 *
 * @param hasAnalyzing   이 버전에 ANALYZING 분석이 있는지
 * @param latestAnalysis 이 버전의 가장 최근 분석 (상태 무관). 분석이 없으면 null
 */
public record VersionAnalysisStateInfo(boolean hasAnalyzing, LatestAnalysis latestAnalysis) {

    /**
     * @param status ANALYZING / COMPLETED / FAILED
     */
    public record LatestAnalysis(Long analysisId, String status, String documentTypeCode, OffsetDateTime startedAt) {

        public static LatestAnalysis from(Analysis a) {
            return new LatestAnalysis(a.getAnalysisId(), a.getStatus().name(), a.getDocumentTypeCode(), a.getStartedAt());
        }
    }
}
