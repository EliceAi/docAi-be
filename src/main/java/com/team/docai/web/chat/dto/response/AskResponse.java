package com.team.docai.web.chat.dto.response;

import com.team.docai.domain.chat.dto.ChatAnswerInfo;
import com.team.docai.domain.chat.entity.AnswerType;

import java.util.List;

/** AI 질문 응답. linkedDocument 는 일반 질문이면 null, partial 은 PARTIAL 일 때만 (아니면 null) */
public record AskResponse(
        Long conversationId,
        Long messageId,
        AnswerType answerType,
        String answer,
        LinkedDocumentResponse linkedDocument,
        List<ReferenceResponse> references,
        PartialResponse partial,
        List<ActionResponse> actions
) {
    public static AskResponse from(ChatAnswerInfo info) {
        return new AskResponse(info.conversationId(), info.messageId(), info.answerType(), info.answer(),
                LinkedDocumentResponse.from(info.linkedDocument()), ReferenceResponse.from(info.references()),
                PartialResponse.from(info.partial()), ActionResponse.from(info.actions()));
    }
}
