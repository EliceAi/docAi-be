package com.team.docai.domain.analysis.dto;

import java.time.OffsetDateTime;

/**
 * A3 AnalysisService.getSummary 의 반환 (COMPLETED 분석만).
 *
 * @param title                  항상 null. analysis 테이블에 칸이 없음. 계약서 이름은 B 의 contract.title 을 쓴다
 * @param needsCheckCount        제외되지 않은 조항 중 NEEDS_CHECK + UNCERTAIN 수 (결과 조회 counts.needsCheck)
 * @param missingCount           빠진 필수 항목 수 (analysis_finding MISSING_ITEM, 결과 조회 counts.missing)
 * @param scheduleCandidateCount 일정 후보 행 수 (날짜 없는 누락 후보 포함)
 */
public record AnalysisSummaryInfo(
        Long analysisId,
        String title,
        OffsetDateTime completedAt,
        long needsCheckCount,
        long missingCount,
        long scheduleCandidateCount
) {
}
