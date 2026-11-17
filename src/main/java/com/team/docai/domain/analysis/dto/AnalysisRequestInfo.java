package com.team.docai.domain.analysis.dto;

import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;

/**
 * 분석 요청 결과. 새로 만든 분석이거나, 같은 버전에 이미 돌고 있던 분석.
 *
 * @param readDone  읽기 단계가 끝난 쪽 묶음 수 (analysis_chunk DONE)
 * @param readTotal 쪽 묶음 수 (5쪽 이하면 1)
 */
public record AnalysisRequestInfo(
        Long analysisId,
        Long contractId,
        AnalysisStatus status,
        AnalysisStep progressStep,
        int readDone,
        int readTotal
) {
}
