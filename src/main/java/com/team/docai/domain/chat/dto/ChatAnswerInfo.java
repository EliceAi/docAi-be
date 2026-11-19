package com.team.docai.domain.chat.dto;

import com.team.docai.domain.chat.entity.AnswerType;
import com.team.docai.domain.chat.entity.ChatAction;
import com.team.docai.domain.chat.entity.ChatPartial;
import com.team.docai.domain.chat.entity.ChatReference;

import java.util.List;

/**
 * AI 질문 결과.
 *
 * @param newConversation 이번 질문으로 대화를 새로 만들었는지 (201 / 200)
 * @param messageId       답변 메시지 ID
 * @param linkedDocument  연결된 계약서. 일반 질문이면 null
 * @param partial         PARTIAL 일 때만, 아니면 null
 */
public record ChatAnswerInfo(
        Long conversationId,
        Long messageId,
        boolean newConversation,
        AnswerType answerType,
        String answer,
        LinkedDocumentInfo linkedDocument,
        List<ChatReference> references,
        ChatPartial partial,
        List<ChatAction> actions
) {
}
