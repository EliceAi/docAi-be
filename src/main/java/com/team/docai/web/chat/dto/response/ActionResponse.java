package com.team.docai.web.chat.dto.response;

import com.team.docai.domain.chat.entity.ActionType;
import com.team.docai.domain.chat.entity.ChatAction;

import java.util.List;
import java.util.Map;

public record ActionResponse(ActionType type, String label, Map<String, Object> params) {
    public static List<ActionResponse> from(List<ChatAction> actions) {
        return actions == null ? null
                : actions.stream().map(a -> new ActionResponse(a.type(), a.label(), a.params())).toList();
    }
}
