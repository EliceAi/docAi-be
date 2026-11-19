package com.team.docai.domain.chat.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 이동 버튼 하나 (actions_json 의 항목). params 는 이동에 필요한 값 (screen, analysisId, clauseId, comparisonId) */
public record ChatAction(ActionType type, String label, Map<String, Object> params) {
    public ChatAction {
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
