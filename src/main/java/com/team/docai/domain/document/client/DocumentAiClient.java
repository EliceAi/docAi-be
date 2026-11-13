package com.team.docai.domain.document.client;

import com.team.docai.domain.document.dto.AnalysisInfo;
import com.team.docai.domain.document.entity.DocumentType;
import java.util.List;
public interface DocumentAiClient {
    AnalysisInfo analyze(DocumentType type,List<byte[]> maskedPages,String scenario);
    String answer(String context,String question);
}
