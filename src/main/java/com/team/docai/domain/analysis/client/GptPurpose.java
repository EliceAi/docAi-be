package com.team.docai.domain.analysis.client;

/**
 * GPT 호출 목적. ai_call_log.purpose 에 그대로 저장된다 (VARCHAR(20)).
 */
public enum GptPurpose {
    EXTRACT,
    JUDGE,
    JUDGE_MORE,
    COMPARE,
    CHAT
}
