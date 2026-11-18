package com.team.docai.domain.analysis.dto;

import java.util.Map;

/**
 * 예시 분석 (GET /api/analyses/samples/{documentTypeCode}). 서버 리소스의 고정 JSON(analysis-samples/{코드}.json)을 그대로 읽는다.
 *
 * @param result        분석 결과 조회의 result 와 같은 모양
 * @param clauseDetails clauseId 별 조항 상세 (조항 상세 조회 응답과 같은 모양). 파일에 적힌 순서를 지킨다
 */
public record SampleAnalysisInfo(
        AnalysisResultInfo.Result result,
        Map<Long, ClauseDetailInfo> clauseDetails
) {
}
