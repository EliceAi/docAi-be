package com.team.docai.domain.chat.dto;

import com.team.docai.domain.chat.entity.AnswerType;
import com.team.docai.domain.chat.entity.ChatAction;
import com.team.docai.domain.chat.entity.ChatPartial;
import com.team.docai.domain.chat.entity.ChatReference;
import com.team.docai.domain.chat.entity.ChatRole;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대화 기록 (오래된 순).
 *
 * @param linkedDocument 연결된 계약서. 일반 질문이거나 계약서가 삭제됐으면 null
 * @param comparisonId   비교 결과에서 시작한 대화면 비교 ID
 * @param hasMore        이전 메시지가 더 있는지
 */
public record ChatHistoryInfo(Long conversationId, LinkedDocumentInfo linkedDocument, Long comparisonId,
                              boolean hasMore, List<MessageInfo> messages) {

    /** answerType·references·partial·actions 는 ASSISTANT 메시지만 (USER 면 null) */
    public record MessageInfo(Long messageId, ChatRole role, String content, AnswerType answerType,
                              List<ChatReference> references, ChatPartial partial, List<ChatAction> actions,
                              OffsetDateTime createdAt) {
    }
}
