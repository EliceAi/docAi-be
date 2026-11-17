package com.team.docai.web.analysis.dto.response;

import com.team.docai.domain.analysis.dto.AnalysisRequestInfo;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;

/**
 * 분석 요청 응답 (202). readProgress 는 읽기 단계 쪽 묶음 진행 (5쪽 이하면 total = 1).
 */
public record AnalysisRequestResponse(
        Long analysisId,
        Long contractId,
        AnalysisStatus status,
        AnalysisStep progressStep,
        ReadProgress readProgress
) {
    public record ReadProgress(int done, int total) {
    }

    public static AnalysisRequestResponse from(AnalysisRequestInfo info) {
        return new AnalysisRequestResponse(info.analysisId(), info.contractId(), info.status(), info.progressStep(),
                new ReadProgress(info.readDone(), info.readTotal()));
    }
}
