package com.team.docai.web.chat.dto.response;

import com.team.docai.domain.chat.entity.ChatReference;
import com.team.docai.domain.chat.entity.ReferenceType;

import java.util.List;

public record ReferenceResponse(ReferenceType type, String label) {
    public static List<ReferenceResponse> from(List<ChatReference> references) {
        return references == null ? null
                : references.stream().map(r -> new ReferenceResponse(r.type(), r.label())).toList();
    }
}
