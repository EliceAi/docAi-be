package com.team.docai.web.chat.dto.response;

import com.team.docai.domain.chat.dto.LinkedDocumentInfo;

import java.time.OffsetDateTime;

public record LinkedDocumentResponse(Long contractId, String title, OffsetDateTime analyzedAt) {
    public static LinkedDocumentResponse from(LinkedDocumentInfo info) {
        return info == null ? null : new LinkedDocumentResponse(info.contractId(), info.title(), info.analyzedAt());
    }
}
