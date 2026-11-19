package com.team.docai.web.chat.dto.response;

import com.team.docai.domain.chat.dto.ChatHistoryInfo;
import com.team.docai.domain.chat.entity.AnswerType;
import com.team.docai.domain.chat.entity.ChatRole;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대화 기록 조회 응답 (오래된 순). answerType·references·partial·actions 는 ASSISTANT 메시지에만 넣는다
 * (USER 메시지는 {@link UserMessage} 로 그 칸이 없다. ASSISTANT 의 partial 은 PARTIAL 이 아니면 null 로 나간다).
 */
public record ChatHistoryResponse(
        Long conversationId,
        LinkedDocumentResponse linkedDocument,
        Long comparisonId,
        boolean hasMore,
        List<Message> messages
) {
    public sealed interface Message permits UserMessage, AssistantMessage {
    }

    public record UserMessage(Long messageId, ChatRole role, String content, OffsetDateTime createdAt)
            implements Message {
    }

    public record AssistantMessage(Long messageId, ChatRole role, String content, AnswerType answerType,
                                   List<ReferenceResponse> references, PartialResponse partial,
                                   List<ActionResponse> actions, OffsetDateTime createdAt) implements Message {
    }

    public static ChatHistoryResponse from(ChatHistoryInfo info) {
        List<Message> messages = info.messages().stream()
                .map(m -> m.role() == ChatRole.USER
                        ? (Message) new UserMessage(m.messageId(), m.role(), m.content(), m.createdAt())
                        : new AssistantMessage(m.messageId(), m.role(), m.content(), m.answerType(),
                                ReferenceResponse.from(m.references()), PartialResponse.from(m.partial()),
                                ActionResponse.from(m.actions()), m.createdAt()))
                .toList();
        return new ChatHistoryResponse(info.conversationId(), LinkedDocumentResponse.from(info.linkedDocument()),
                info.comparisonId(), info.hasMore(), messages);
    }
}
