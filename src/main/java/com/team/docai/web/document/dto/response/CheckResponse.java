package com.team.docai.web.document.dto.response;

import com.team.docai.domain.document.dto.*;
import com.team.docai.domain.document.entity.*;
import java.util.UUID;
import java.time.OffsetDateTime;
public record CheckResponse(UUID id,String title,DocumentType documentType,CheckStatus status,int pageCount,boolean archived,int revision,UUID previousId,AnalysisInfo analysis,String failureReason,OffsetDateTime createdAt) {
    public static CheckResponse from(CheckInfo i){return new CheckResponse(i.id(),i.title(),i.documentType(),i.status(),i.pageCount(),i.archived(),i.revision(),i.previousId(),i.analysis(),i.failureReason(),i.createdAt());}
}
