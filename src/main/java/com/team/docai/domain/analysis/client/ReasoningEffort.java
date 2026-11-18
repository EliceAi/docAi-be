package com.team.docai.domain.analysis.client;

import java.util.Locale;

/**
 * 추론 모델의 reasoning_effort (gpt-5.5 모델 문서: none, low, medium(기본), high, xhigh).
 * 추론 모델이 아니면(gpt-4o 등) 보내지 않는다.
 */
public enum ReasoningEffort {
    NONE, LOW, MEDIUM, HIGH, XHIGH;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
