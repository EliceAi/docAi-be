package com.team.docai.domain.chat.dto;

/**
 * AI 질문. message 는 앞뒤 공백을 지운 값, screen 은 S01~S15.
 *
 * @param conversationId 이어서 질문할 대화. null 이면 새 대화
 * @param contractId     연결할 내 계약서 (새 대화에서만 정함)
 * @param analysisId     참고할 분석 (새 대화에서만 정함, 기존 대화에서는 무시)
 * @param clauseId       보고 있던 조항 (질문 메시지의 refs_json 에 저장)
 * @param comparisonId   보고 있던 비교 (새 대화에서만 정함, 기존 대화에서는 무시)
 */
public record AskCommand(Long conversationId, String message, String screen, Long contractId, Long analysisId,
                         Long clauseId, Long comparisonId) {
}
