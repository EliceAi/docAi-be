package com.team.docai.domain.analysis.dto;

import java.time.OffsetDateTime;

/**
 * A1 AnalysisService.getRef 의 반환. status: ANALYZING / COMPLETED / FAILED.
 */
public record AnalysisRefInfo(
        Long analysisId,
        Long versionId,
        Long contractId,
        String status,
        OffsetDateTime completedAt
) {
}
