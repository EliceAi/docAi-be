package com.team.docai.domain.analysis.client;

import java.util.UUID;

/**
 * ai_call_log 에 남길 호출 맥락. 해당 없는 값은 null.
 */
public record GptContext(UUID userId, Long analysisId, Long chunkId, Long comparisonId, Long conversationId) {

    public static GptContext none() {
        return new GptContext(null, null, null, null, null);
    }

    public static GptContext analysis(UUID userId, Long analysisId) {
        return new GptContext(userId, analysisId, null, null, null);
    }

    public static GptContext chunk(UUID userId, Long analysisId, Long chunkId) {
        return new GptContext(userId, analysisId, chunkId, null, null);
    }

    public static GptContext comparison(UUID userId, Long comparisonId) {
        return new GptContext(userId, null, null, comparisonId, null);
    }

    public static GptContext conversation(UUID userId, Long conversationId) {
        return new GptContext(userId, null, null, null, conversationId);
    }
}
