package com.team.docai.domain.chat.service;

import com.team.docai.domain.chat.entity.ChatMessage;
import com.team.docai.domain.chat.entity.Conversation;
import com.team.docai.domain.chat.repository.ChatMessageRepository;
import com.team.docai.domain.chat.repository.ConversationRepository;
import com.team.docai.domain.contract.service.ContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * AI 질문 저장 (GPT 응답을 받은 뒤의 짧은 쓰기 트랜잭션). GPT 를 부르는 ChatPipeline 과 다른 빈이라 @Transactional 이 적용된다.
 * <ul>
 *   <li>새 대화면 대화를 만들고, 질문(USER)과 답변(ASSISTANT)을 함께 저장한다. GPT 가 실패하면 여기까지 오지 않으므로
 *       아무것도 남지 않는다.</li>
 *   <li>대화 요약은 {@link ConversationRepository#updateSummaryIfUnchanged} 로만 바꾼다. 같은 대화에 동시에 온 다른 질문이
 *       먼저 요약을 바꿨으면 0행이 되고, 이 요약은 버린다 (다음 질문이 최근 대화와 함께 다시 요약한다).</li>
 *   <li>새 대화에 계약서를 붙일 때는 이 트랜잭션 맨 앞에서 B {@code ContractService.getSummary} 로 계약서를 다시 확인한다.
 *       맥락을 읽은 뒤 GPT 를 기다리는 동안 수정본 확정이 ⑤ 대화 옮기기·⑦ 임시 계약서 삭제를 커밋하면, 확인 없이는
 *       삭제된 임시 계약서에 새 대화가 붙어 저장된다. 없어졌으면 CONTRACT_NOT_FOUND 로 끝내고 질문·답변도 저장하지 않는다.</li>
 * </ul>
 * 남은 한계: 이 확인과 INSERT 사이의 아주 짧은 구간에 확정이 커밋되면 여전히 삭제된 계약서에 붙을 수 있다 (행을 잠그지 않으므로).
 * B 와 합의되면 확인 대신 {@code ContractService.lockForUpdate(contractId)} 로 계약서 행을 잠근 뒤 확인한다
 * (확정도 같은 행을 먼저 잠그므로 둘이 차례로 실행됨).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ChatWriter {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ContractService contractService;
    private final Clock clock;

    record Saved(Long conversationId, Long messageId, boolean newConversation) {
    }

    @Transactional
    public Saved save(ChatContext ctx, ChatAnswerValidator.Result answer) {
        Long conversationId = ctx.conversationId();
        boolean created = conversationId == null;
        if (created && ctx.contractId() != null) {
            // 남의 것·삭제됨이면 B 가 CONTRACT_NOT_FOUND 를 던지고 이 트랜잭션은 아무것도 남기지 않는다
            contractService.getSummary(ctx.userId(), ctx.contractId());
        }
        if (created) {
            conversationId = conversationRepository.save(Conversation.create(ctx.userId(), ctx.contractId(),
                    ctx.analysisIdToStore(), ctx.comparisonId(), answer.summary())).getConversationId();
        }
        chatMessageRepository.save(ChatMessage.question(conversationId, ctx.question(), ctx.screen(), ctx.clauseId()));
        ChatMessage reply = chatMessageRepository.save(ChatMessage.answer(conversationId, answer.answer(),
                answer.answerType(), answer.references(), answer.partial(), answer.actions()));

        if (!created) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            int changed = answer.summary() == null ? 0
                    : conversationRepository.updateSummaryIfUnchanged(conversationId, ctx.previousSummary(),
                    answer.summary(), now);
            if (changed == 0) {
                if (answer.summary() != null) {
                    log.info("대화 요약을 쓰지 않음 (동시에 온 다른 질문이 먼저 바꿈): conversationId={}", conversationId);
                }
                conversationRepository.touch(conversationId, now);
            }
        }
        return new Saved(conversationId, reply.getMessageId(), created);
    }
}
