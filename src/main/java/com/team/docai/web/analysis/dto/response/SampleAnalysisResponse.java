package com.team.docai.web.analysis.dto.response;

import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.dto.SampleAnalysisInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 예시 분석 조회 응답 (GET /api/analyses/samples/{documentTypeCode}). analysisId·contractId 는 항상 null, status 는 COMPLETED,
 * sample 은 true. clauseDetails 의 키는 clauseId (JSON 객체 키라 글자).
 */
public record SampleAnalysisResponse(
        Long analysisId,
        Long contractId,
        AnalysisStatus status,
        boolean sample,
        AnalysisResultResponse.Result result,
        Map<String, ClauseDetailResponse> clauseDetails
) {
    public static SampleAnalysisResponse from(SampleAnalysisInfo info) {
        Map<String, ClauseDetailResponse> details = new LinkedHashMap<>();
        if (info.clauseDetails() != null) {
            info.clauseDetails().forEach((id, d) -> details.put(String.valueOf(id), ClauseDetailResponse.from(d)));
        }
        return new SampleAnalysisResponse(null, null, AnalysisStatus.COMPLETED, true,
                AnalysisResultResponse.Result.from(info.result()), details);
    }
}
