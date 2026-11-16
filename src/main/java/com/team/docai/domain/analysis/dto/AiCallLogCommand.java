package com.team.docai.domain.analysis.dto;

import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.entity.AiCallStatus;

import java.util.UUID;

/**
 * GPT 호출 시도 1회의 기록. 요청 본문(프롬프트·이미지)은 담지 않는다.
 */
public record AiCallLogCommand(
        UUID userId,
        GptPurpose purpose,
        Long analysisId,
        Long chunkId,
        Long comparisonId,
        Long conversationId,
        String model,
        Integer inputTokens,
        Integer cachedInputTokens,
        Integer outputTokens,
        Integer latencyMs,
        AiCallStatus status,
        String errorMessage
) {
}
