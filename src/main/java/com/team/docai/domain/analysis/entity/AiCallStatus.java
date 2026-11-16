package com.team.docai.domain.analysis.entity;

/**
 * ai_call_log.status. RETRY: 이 시도는 실패했고 다시 시도함, FAILED: 마지막 시도까지 실패.
 */
public enum AiCallStatus {
    OK,
    RETRY,
    FAILED
}
