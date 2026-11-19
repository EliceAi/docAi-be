package com.team.docai.domain.chat.service;

import com.team.docai.domain.analysis.dto.AnalysisSummaryInfo;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.chat.dto.ChatHistoryInfo;
import com.team.docai.domain.chat.entity.AnswerType;
import com.team.docai.domain.chat.entity.ChatMessage;
import com.team.docai.domain.chat.entity.ChatPartial;
import com.team.docai.domain.chat.entity.ChatReference;
import com.team.docai.domain.chat.entity.ChatRole;
import com.team.docai.domain.chat.entity.Conversation;
import com.team.docai.domain.chat.entity.ReferenceType;
import com.team.docai.domain.chat.repository.ChatMessageRepository;
import com.team.docai.domain.chat.repository.ConversationRepository;
import com.team.docai.domain.contract.dto.ContractSummaryInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 대화 기록 조회(쪽 나누기·연결된 계약서)와 A7 moveContract. 저장소는 가짜. */
class ChatServiceTest {

    static final UUID USER = UUID.randomUUID();

    final Clock clock = Clock.fixed(Instant.parse("2026-11-12T05:25:00Z"), ZoneId.of("Asia/Seoul"));
    final ConversationRepository conversations = mock(ConversationRepository.class);
    final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    final ContractService contractService = mock(ContractService.class);
    final AnalysisService analysisService = mock(AnalysisService.class);
    final ChatService service = new ChatService(conversations, messages, contractService,
            new LinkedDocuments(analysisService, clock), clock);

    Conversation conversation(Long contractId) {
        Conversation c = Conversation.create(USER, contractId, null, 9L, "요약");
        ReflectionTestUtils.setField(c, "conversationId", 3L);
        when(conversations.findByConversationIdAndUserId(3L, USER)).thenReturn(Optional.of(c));
        return c;
    }

    static ChatMessage message(long id, ChatRole role) {
        ChatMessage m = role == ChatRole.USER
                ? ChatMessage.question(3L, "질문 " + id, "S08", null)
                : ChatMessage.answer(3L, "답변 " + id, AnswerType.PARTIAL,
                List.of(new ChatReference(ReferenceType.LAW_DB, "근로기준법 제20조")),
                new ChatPartial(List.of("a"), List.of("b")), List.of());
        ReflectionTestUtils.setField(m, "messageId", id);
        ReflectionTestUtils.setField(m, "createdAt", OffsetDateTime.parse("2026-11-12T05:25:0" + (id % 10) + "Z"));
        return m;
    }

    @Test
    void 오래된_순으로_주고_size보다_많으면_hasMore() {
        conversation(12L);
        when(contractService.getSummary(USER, 12L)).thenReturn(new ContractSummaryInfo(12L, "카페 알바 근로계약서", true, 88L));
        when(analysisService.getSummary(88L)).thenReturn(Optional.of(new AnalysisSummaryInfo(88L, null,
                OffsetDateTime.parse("2026-11-12T05:20:00Z"), 0, 0, 0)));
        // 최근 것부터 size + 1 개
        when(messages.findByConversationIdOrderByMessageIdDesc(eq(3L), any(Pageable.class)))
                .thenReturn(List.of(message(43, ChatRole.ASSISTANT), message(42, ChatRole.USER), message(41, ChatRole.ASSISTANT)));

        ChatHistoryInfo info = service.getMessages(USER, 3L, null, 2);

        assertThat(info.hasMore()).isTrue();
        assertThat(info.messages()).extracting(ChatHistoryInfo.MessageInfo::messageId).containsExactly(42L, 43L);
        ChatHistoryInfo.MessageInfo user = info.messages().get(0);
        assertThat(user.answerType()).isNull();
        assertThat(user.references()).isNull();
        assertThat(user.actions()).isNull();
        assertThat(user.createdAt()).isEqualTo(OffsetDateTime.parse("2026-11-12T14:25:02+09:00"));
        ChatHistoryInfo.MessageInfo assistant = info.messages().get(1);
        assertThat(assistant.answerType()).isEqualTo(AnswerType.PARTIAL);
        assertThat(assistant.partial().unknown()).containsExactly("b");
        assertThat(assistant.actions()).isEmpty();
        assertThat(info.linkedDocument().title()).isEqualTo("카페 알바 근로계약서");
        assertThat(info.linkedDocument().analyzedAt()).isEqualTo(OffsetDateTime.parse("2026-11-12T14:20:00+09:00"));
        assertThat(info.comparisonId()).isEqualTo(9L);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(messages).findByConversationIdOrderByMessageIdDesc(eq(3L), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(3);
    }

    @Test
    void beforeMessageId가_있으면_그_앞만_보고_size는_기본_30_범위_1에서_100() {
        conversation(null);
        when(messages.findByConversationIdAndMessageIdLessThanOrderByMessageIdDesc(eq(3L), eq(41L), any(Pageable.class)))
                .thenReturn(List.of(message(40, ChatRole.USER)));

        ChatHistoryInfo info = service.getMessages(USER, 3L, 41L, null);
        assertThat(info.hasMore()).isFalse();
        assertThat(info.linkedDocument()).isNull();
        assertThat(info.messages()).extracting(ChatHistoryInfo.MessageInfo::messageId).containsExactly(40L);

        service.getMessages(USER, 3L, 41L, 0);
        service.getMessages(USER, 3L, 41L, 1000);
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(messages, org.mockito.Mockito.times(3))
                .findByConversationIdAndMessageIdLessThanOrderByMessageIdDesc(eq(3L), eq(41L), pages.capture());
        assertThat(pages.getAllValues()).extracting(Pageable::getPageSize).containsExactly(31, 2, 101);
    }

    @Test
    void 연결된_계약서가_삭제됐으면_linkedDocument는_null() {
        conversation(12L);
        when(contractService.getSummary(USER, 12L)).thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
        when(messages.findByConversationIdOrderByMessageIdDesc(eq(3L), any(Pageable.class))).thenReturn(new ArrayList<>());

        ChatHistoryInfo info = service.getMessages(USER, 3L, null, null);

        assertThat(info.linkedDocument()).isNull();
        assertThat(info.messages()).isEmpty();
    }

    @Test
    void 내_대화가_아니면_404_CONVERSATION_NOT_FOUND() {
        when(conversations.findByConversationIdAndUserId(3L, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMessages(USER, 3L, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
        verify(messages, never()).findByConversationIdOrderByMessageIdDesc(anyLong(), any());
    }

    @Test
    void moveContract는_임시_계약서의_대화를_이전_계약서로_옮긴다() {
        service.moveContract(21L, 15L);
        verify(conversations).moveContract(eq(21L), eq(15L), any());
    }

    @Test
    void moveContract는_같은_계약서나_null이면_아무것도_하지_않는다() {
        service.moveContract(15L, 15L);
        service.moveContract(null, 15L);
        service.moveContract(21L, null);
        verify(conversations, never()).moveContract(any(), any(), any());
    }
}
