package com.team.docai.domain.document.dto;

import com.team.docai.domain.document.entity.*;
import java.util.UUID;
import java.time.OffsetDateTime;
public record CheckInfo(UUID id,String title,DocumentType documentType,CheckStatus status,int pageCount,boolean archived,int revision,UUID previousId,AnalysisInfo analysis,String failureReason,OffsetDateTime createdAt) {}
