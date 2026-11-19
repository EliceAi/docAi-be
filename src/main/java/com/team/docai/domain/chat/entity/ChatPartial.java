package com.team.docai.domain.chat.entity;

import java.util.List;

/** 일부만 답할 때 (partial_json): confirmed 계약서로 확인한 것, unknown 확인할 수 없는 것 */
public record ChatPartial(List<String> confirmed, List<String> unknown) {
    public ChatPartial {
        confirmed = confirmed == null ? List.of() : List.copyOf(confirmed);
        unknown = unknown == null ? List.of() : List.copyOf(unknown);
    }
}
