package com.team.docai.domain.chat.dto;

import java.time.OffsetDateTime;

/**
 * 대화에 연결된 계약서 (AI 질문 응답과 대화 기록 조회가 같은 모양).
 *
 * @param title      계약서 이름 (분석 전이면 null)
 * @param analyzedAt 그 계약서 최신 버전의 최신 완료 분석 시각 (완료된 분석이 없으면 null)
 */
public record LinkedDocumentInfo(Long contractId, String title, OffsetDateTime analyzedAt) {
}
