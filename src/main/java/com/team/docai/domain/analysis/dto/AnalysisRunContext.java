package com.team.docai.domain.analysis.dto;

import java.util.UUID;

/**
 * 백그라운드 분석 한 번의 맥락. 분석 요청 때 정해지고 실행 중에 바뀌지 않는다.
 *
 * @param userId           요청한 사용자 (알림 받는 사람, GPT 호출 기록)
 * @param versionId        분석하는 버전 (요청 시점의 최신 버전)
 * @param documentTypeCode 요청 시점의 문서 종류 (analysis.document_type_code)
 */
public record AnalysisRunContext(
        Long analysisId,
        UUID userId,
        Long contractId,
        Long versionId,
        String documentTypeCode
) {
}
