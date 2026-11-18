package com.team.docai.domain.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 분석이 정하는 계약서 이름. 1차가 읽은 문서 제목(extraction_json.documentTitle), 없으면 문서 종류 이름(근로계약서 등).
 * 완료 알림 부제(AnalysisWriter.complete)와 계약서 이름 넣기(B4 setTitleIfEmpty, 완료 뒤)가 같은 규칙을 쓴다.
 */
public final class AnalysisTitle {

    private AnalysisTitle() {
    }

    public static String resolve(ObjectMapper objectMapper, String extractionJson, String documentTypeCode) {
        if (extractionJson != null && !extractionJson.isBlank()) {
            try {
                return resolve(objectMapper.readTree(extractionJson), documentTypeCode);
            } catch (JsonProcessingException e) {
                // 읽지 못하면 문서 종류 이름
            }
        }
        return ExtractCatalog.documentTypeName(documentTypeCode);
    }

    public static String resolve(JsonNode extraction, String documentTypeCode) {
        JsonNode t = extraction == null ? null : extraction.path("documentTitle");
        if (t != null && t.isTextual() && !t.asText().isBlank()) {
            return t.asText().trim();
        }
        return ExtractCatalog.documentTypeName(documentTypeCode);
    }
}
