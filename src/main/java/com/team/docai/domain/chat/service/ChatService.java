package com.team.docai.domain.chat.service;

import com.team.docai.domain.chat.dto.ChatHistoryInfo;
import com.team.docai.domain.chat.dto.LinkedDocumentInfo;
import com.team.docai.domain.chat.entity.ChatMessage;
import com.team.docai.domain.chat.entity.ChatRole;
import com.team.docai.domain.chat.entity.Conversation;
import com.team.docai.domain.chat.repository.ChatMessageRepository;
import com.team.docai.domain.chat.repository.ConversationRepository;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 챗봇: 대화 기록 조회와 B 가 부르는 연결 메서드(A7 moveContract). AI 질문(GPT 호출)은 {@link ChatPipeline}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    static final int DEFAULT_PAGE_SIZE = 30;
    static final int MAX_PAGE_SIZE = 100;

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ContractService contractService;
    private final LinkedDocuments linkedDocuments;
    private final Clock clock;

    /**
     * 대화 기록 조회 (오래된 순). beforeMessageId 가 있으면 그보다 앞의 메시지만 (더 보기).
     * size 는 기본 30, 1~100 으로 맞춘다 (명세 에러 표에 400 이 없어 범위 밖 값은 거절하지 않고 맞춤).
     * <p>
     * 트랜잭션 없이 실행한다: 연결된 계약서가 삭제됐으면 B 의 getSummary 가 CONTRACT_NOT_FOUND 를 던지는데, 같은 트랜잭션에서 그
     * 예외를 받아 넘기면 트랜잭션이 rollback-only 가 되어 응답이 500 이 된다. 그래서 각 읽기를 따로 하고, 삭제된 계약서는
     * linkedDocument = null 로 둔다 (에러 표에는 401·CONVERSATION_NOT_FOUND 뿐).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatHistoryInfo getMessages(UUID userId, Long conversationId, Long beforeMessageId, Integer size) {
        Conversation conversation = conversationRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND));
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(MAX_PAGE_SIZE, size));

        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<ChatMessage> latestFirst = beforeMessageId == null
                ? chatMessageRepository.findByConversationIdOrderByMessageIdDesc(conversationId, page)
                : chatMessageRepository.findByConversationIdAndMessageIdLessThanOrderByMessageIdDesc(
                        conversationId, beforeMessageId, page);
        boolean hasMore = latestFirst.size() > pageSize;
        List<ChatHistoryInfo.MessageInfo> messages = new ArrayList<>();
        for (int i = Math.min(latestFirst.size(), pageSize) - 1; i >= 0; i--) {
            messages.add(toInfo(latestFirst.get(i)));
        }
        return new ChatHistoryInfo(conversation.getConversationId(), linkedDocument(userId, conversation),
                conversation.getComparisonId(), hasMore, List.copyOf(messages));
    }

    /**
     * A7 수정본 확정 ⑤. 임시 계약서(fromContractId)에 연결된 대화를 이전 계약서(toContractId)로 옮긴다.
     * 대화의 분석·비교 연결은 그대로 둔다 (확정 ②에서 수정본 버전도 이전 계약서로 옮겨지므로 분석은 같은 계약서의 것이 된다).
     * B 의 확정 트랜잭션 안에서만 부른다 (없으면 예외). 영속성 컨텍스트는 비우지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveContract(Long fromContractId, Long toContractId) {
        if (fromContractId == null || toContractId == null || fromContractId.equals(toContractId)) {
            return;
        }
        conversationRepository.moveContract(fromContractId, toContractId, OffsetDateTime.now(clock));
    }

    private LinkedDocumentInfo linkedDocument(UUID userId, Conversation conversation) {
        if (conversation.getContractId() == null) {
            return null;
        }
        try {
            return linkedDocuments.from(contractService.getSummary(userId, conversation.getContractId()));
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.CONTRACT_NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private ChatHistoryInfo.MessageInfo toInfo(ChatMessage m) {
        OffsetDateTime createdAt = m.getCreatedAt() == null ? null
                : m.getCreatedAt().atZoneSameInstant(clock.getZone()).toOffsetDateTime();
        if (m.getRole() == ChatRole.USER) {
            return new ChatHistoryInfo.MessageInfo(m.getMessageId(), m.getRole(), m.getContent(), null, null, null, null,
                    createdAt);
        }
        return new ChatHistoryInfo.MessageInfo(m.getMessageId(), m.getRole(), m.getContent(), m.getAnswerType(),
                m.references(), m.getPartialJson(), m.actions(), createdAt);
    }
}
