package com.team.docai.domain.chat.entity;

/** chat_message.answer_type. PARTIAL: 일부만 답함, OUT_OF_SCOPE: 지원하지 않는 문서·질문 */
public enum AnswerType {
    ANSWERED,
    PARTIAL,
    OUT_OF_SCOPE
}
