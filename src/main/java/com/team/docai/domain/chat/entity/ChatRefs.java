package com.team.docai.domain.chat.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * chat_message.refs_json. 질문(USER)에는 보고 있던 조항(clauseId, AI 질문 명세 요청 필드), 답변(ASSISTANT)에는 참고한 정보 목록.
 * 없는 칸은 JSON 에 넣지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRefs(List<ChatReference> references, Long clauseId) {

    public static ChatRefs question(Long clauseId) {
        return new ChatRefs(null, clauseId);
    }

    public static ChatRefs answer(List<ChatReference> references) {
        return new ChatRefs(List.copyOf(references), null);
    }
}
