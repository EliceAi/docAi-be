package com.team.docai.domain.chat.service;

import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptContentPart;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptFailureReason;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.domain.analysis.dto.AnalysisChatInfo;
import com.team.docai.domain.analysis.dto.AnalysisSummaryInfo;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.analysis.service.query.AnalysisChatQueryService;
import com.team.docai.domain.chat.dto.AskCommand;
import com.team.docai.domain.chat.dto.ChatAnswerInfo;
import com.team.docai.domain.chat.dto.ChatGptResponse;
import com.team.docai.domain.chat.entity.ActionType;
import com.team.docai.domain.chat.entity.AnswerType;
import com.team.docai.domain.chat.entity.ChatMessage;
import com.team.docai.domain.chat.entity.ChatReference;
import com.team.docai.domain.chat.entity.ChatRole;
import com.team.docai.domain.chat.entity.Conversation;
import com.team.docai.domain.chat.entity.ReferenceType;
import com.team.docai.domain.chat.repository.ChatMessageRepository;
import com.team.docai.domain.chat.repository.ConversationRepository;
import com.team.docai.domain.comparison.dto.ComparisonForChatInfo;
import com.team.docai.domain.comparison.dto.ComparisonForChatInfo.Change;
import com.team.docai.domain.comparison.service.ComparisonService;
import com.team.docai.domain.contract.dto.ContractSummaryInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.service.LawSearchService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 질문 흐름을 가짜 GptClient·저장소로 확인한다 (DB·GPT 없음): 답변 종류(ANSWERED/PARTIAL/OUT_OF_SCOPE), 준 근거만 인용,
 * 단정 표현·내부 키 감추기, 이동 버튼, 대화 이어 가기(최근 대화·요약), 다른 계약서 409, 확인 실패 시 GPT·저장 안 함.
 */
class ChatPipelineTest {

    static final UUID USER = UUID.randomUUID();
    static final long CONTRACT = 12L;
    static final long ANALYSIS = 88L;
    static final long CLAUSE_TERM = 701L;
    static final long CLAUSE_PENALTY = 707L;
    static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-11-12T05:20:00Z");

    final Clock clock = Clock.fixed(Instant.parse("2026-11-12T05:25:00Z"), ZoneId.of("Asia/Seoul"));
    final ConversationRepository conversations = mock(ConversationRepository.class);
    final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    final ContractService contractService = mock(ContractService.class);
    final AnalysisChatQueryService analysisQuery = mock(AnalysisChatQueryService.class);
    final ComparisonService comparisonService = mock(ComparisonService.class);
    final LawSearchService lawSearch = mock(LawSearchService.class);
    final AnalysisService analysisService = mock(AnalysisService.class);
    final GptClient gpt = mock(GptClient.class);

    final List<ChatMessage> saved = new ArrayList<>();
    final List<Conversation> savedConversations = new ArrayList<>();
    final AtomicLong ids = new AtomicLong(40);

    ChatPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = pipeline(1000);
        when(contractService.getSummary(USER, CONTRACT))
                .thenReturn(new ContractSummaryInfo(CONTRACT, "카페 알바 근로계약서", true, ANALYSIS));
        when(analysisService.getSummary(ANALYSIS))
                .thenReturn(Optional.of(new AnalysisSummaryInfo(ANALYSIS, null, COMPLETED_AT, 1, 0, 2)));
        when(analysisQuery.findForChat(USER, ANALYSIS)).thenReturn(Optional.of(completedAnalysis()));
        when(lawSearch.findLawIds(anyString())).thenReturn(List.of());
        when(lawSearch.searchWithDailyTerms(any(), anyCollection(), any(), any())).thenReturn(List.of());
        when(conversations.save(any())).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "conversationId", 3L);
            savedConversations.add(c);
            return c;
        });
        when(messages.save(any())).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "messageId", ids.getAndIncrement());
            saved.add(m);
            return m;
        });
    }

    ChatPipeline pipeline(int limit) {
        LinkedDocuments linked = new LinkedDocuments(analysisService, clock);
        ChatContextReader reader = new ChatContextReader(conversations, messages, contractService, analysisQuery,
                comparisonService, lawSearch, linked);
        return new ChatPipeline(new ChatRateLimiter(clock, limit), reader, gpt, new ChatWriter(conversations, messages, contractService, clock));
    }

    static AnalysisChatInfo completedAnalysis() {
        return new AnalysisChatInfo(ANALYSIS, CONTRACT, AnalysisStatus.COMPLETED, "EMPLOYMENT", COMPLETED_AT,
                List.of(new AnalysisChatInfo.KeyTerm("임금", "시급 10,000원", "NEEDS_CHECK")),
                List.of(new AnalysisChatInfo.Clause(CLAUSE_TERM, "c1", "제1조", "계약 기간", "OK", null, "3개월 계약", 1,
                                null, null, "계약기간: 2026.12.01 ~ 2027.02.28"),
                        new AnalysisChatInfo.Clause(CLAUSE_PENALTY, "c7", "제7조", "교육비 반환", "NEEDS_CHECK",
                                "1년 안에 퇴사하면 교육비 30만 원 반환", "교육비 반환", 2, "퇴사하면 교육비를 내야 해요.",
                                "계약 기간이 3개월이라 사실상 교육비를 내야 해요.",
                                "근로자가 1년 이내에 퇴사하는 경우 교육비 30만 원을 반환한다.")),
                List.of(new AnalysisChatInfo.Finding("MISSING_ITEM", "휴일", "주휴일을 꼭 적어야 해요", null)),
                List.of(new AnalysisChatInfo.LawRef(CLAUSE_PENALTY, 5001L, "근로기준법 제20조", "위약 예정의 금지",
                        "사용자는 근로계약 불이행에 대한 위약금 또는 손해배상액을 예정하는 계약을 체결하지 못한다.")));
    }

    static ChatGptResponse gptAnswer(String type, String answer, List<String> cited, List<String> used,
                                     boolean mentionsLaw, ChatGptResponse.Partial partial,
                                     List<ChatGptResponse.Action> actions) {
        return new ChatGptResponse(type, answer, cited, used, mentionsLaw,
                partial == null ? new ChatGptResponse.Partial(List.of(), List.of()) : partial, actions,
                "사용자는 교육비 반환 조항을 물었다.");
    }

    void gptReturns(ChatGptResponse response) {
        when(gpt.call(eq(GptPurpose.CHAT), any(GptRequest.class), eq(ChatGptResponse.class), any(GptContext.class)))
                .thenReturn(response);
    }

    GptRequest sentRequest() {
        ArgumentCaptor<GptRequest> captor = ArgumentCaptor.forClass(GptRequest.class);
        verify(gpt).call(eq(GptPurpose.CHAT), captor.capture(), eq(ChatGptResponse.class), any(GptContext.class));
        return captor.getValue();
    }

    static String text(GptRequest request) {
        return request.userContent().stream().map(p -> ((GptContentPart.Text) p).text()).collect(Collectors.joining("\n"));
    }

    static AskCommand newQuestion(String message, Long clauseId) {
        return new AskCommand(null, message, "S09", CONTRACT, ANALYSIS, clauseId, null);
    }

    // ---- ANSWERED ----

    @Test
    void 새_대화_ANSWERED는_서버가_준_근거만_LAW_DB로_넣고_버튼을_서버가_만든다() {
        gptReturns(gptAnswer("ANSWERED", "<b>제7조</b>는 1년 안에 퇴사하면 교육비를 물게 해요 [L1]. 근로기준법 제20조와 함께 확인이 필요해요.",
                List.of("L1"), List.of("MY_DOCUMENT", "CURRENT_SCREEN"), true, null,
                List.of(new ChatGptResponse.Action("OPEN_CLAUSE", "c7"), new ChatGptResponse.Action("OPEN_SCREEN", "S13"))));

        ChatAnswerInfo info = pipeline.ask(USER, newQuestion("교육비 조항 괜찮아?", CLAUSE_PENALTY));

        assertThat(info.newConversation()).isTrue();
        assertThat(info.conversationId()).isEqualTo(3L);
        assertThat(info.answerType()).isEqualTo(AnswerType.ANSWERED);
        assertThat(info.answer()).isEqualTo("<b>제7조</b>는 1년 안에 퇴사하면 교육비를 물게 해요. 근로기준법 제20조와 함께 확인이 필요해요.");
        assertThat(info.partial()).isNull();
        assertThat(info.references()).containsExactly(
                new ChatReference(ReferenceType.CURRENT_SCREEN, "제7조"),
                new ChatReference(ReferenceType.MY_DOCUMENT, "카페 알바 근로계약서"),
                new ChatReference(ReferenceType.LAW_DB, "근로기준법 제20조"));
        assertThat(info.actions()).hasSize(2);
        assertThat(info.actions().get(0).type()).isEqualTo(ActionType.OPEN_CLAUSE);
        assertThat(info.actions().get(0).label()).isEqualTo("근거 조항 보기 (제7조)");
        assertThat(info.actions().get(0).params()).containsEntry("analysisId", ANALYSIS).containsEntry("clauseId", CLAUSE_PENALTY);
        assertThat(info.actions().get(1).params()).containsEntry("screen", "S13").containsEntry("analysisId", ANALYSIS);
        assertThat(info.linkedDocument().contractId()).isEqualTo(CONTRACT);
        assertThat(info.linkedDocument().analyzedAt()).isEqualTo(OffsetDateTime.parse("2026-11-12T14:20:00+09:00"));

        // 대화: 계약서·요청한 분석 연결, 요약 저장. 질문·답변 둘 다 저장, 질문에 보던 조항
        assertThat(savedConversations).hasSize(1);
        assertThat(savedConversations.get(0).getContractId()).isEqualTo(CONTRACT);
        assertThat(savedConversations.get(0).getAnalysisId()).isEqualTo(ANALYSIS);
        assertThat(savedConversations.get(0).getSummary()).isEqualTo("사용자는 교육비 반환 조항을 물었다.");
        assertThat(saved).extracting(ChatMessage::getRole).containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
        assertThat(saved.get(0).getContent()).isEqualTo("교육비 조항 괜찮아?");
        assertThat(saved.get(0).getScreen()).isEqualTo("S09");
        assertThat(saved.get(0).getRefsJson().clauseId()).isEqualTo(CLAUSE_PENALTY);
        assertThat(saved.get(1).getAnswerType()).isEqualTo(AnswerType.ANSWERED);
        assertThat(saved.get(1).references()).isEqualTo(info.references());
        assertThat(info.messageId()).isEqualTo(saved.get(1).getMessageId());

        // 보낸 것: 이미지 없음, 분석 요약·보던 조항 원문·근거 [L1], 새 대화라 최근 대화 없음
        GptRequest request = sentRequest();
        assertThat(request.userContent()).allMatch(p -> p instanceof GptContentPart.Text);
        String sent = text(request);
        assertThat(sent).contains("[현재 화면] S09 (조항 상세)", "카페 알바 근로계약서", "[보고 있는 조항] c7",
                "근로자가 1년 이내에 퇴사하는 경우", "[L1] 근로기준법 제20조 (위약 예정의 금지)", "교육비 조항 괜찮아?");
        assertThat(sent).contains("[최근 대화]\n- 없음");
        assertThat(request.systemPrompt()).contains("\"제공된 근거\"", "\"규칙 결과\"", "단정 표현");
    }

    @Test
    void 준_적_없는_인용은_지우고_법적_판단에_근거가_없으면_PARTIAL로_낮춘다() {
        gptReturns(gptAnswer("ANSWERED", "교육비 반환은 문제가 될 수 있어요. 민법 제398조도 함께 봐야 해요.",
                List.of("L9", "G1"), List.of("MY_DOCUMENT"), true, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, newQuestion("교육비 조항 괜찮아?", null));

        assertThat(info.answerType()).isEqualTo(AnswerType.PARTIAL);
        // 준 적 없는 법 조문 이름이 든 문장은 지움
        assertThat(info.answer()).isEqualTo("교육비 반환은 문제가 될 수 있어요.");
        assertThat(info.references()).extracting(ChatReference::type).doesNotContain(ReferenceType.LAW_DB);
        assertThat(info.partial()).isNotNull();
        assertThat(info.partial().unknown()).contains(ChatAnswerValidator.NO_LAW_UNKNOWN);
        assertThat(saved.get(1).getPartialJson()).isEqualTo(info.partial());
    }

    @Test
    void PARTIAL은_확인한_것과_알_수_없는_것을_정리해_준다() {
        gptReturns(gptAnswer("PARTIAL", "계약 기간은 3개월이에요. 실제 교육비가 얼마인지는 계약서에 없어요.",
                List.of("L1"), List.of("MY_DOCUMENT"), true,
                new ChatGptResponse.Partial(List.of("c1 계약 기간은 3개월이에요.", "이 조항은 무효예요."),
                        List.of("실제 교육비 [L1]", "실제 교육비 [L1]")),
                List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, newQuestion("교육비 얼마야?", null));

        assertThat(info.answerType()).isEqualTo(AnswerType.PARTIAL);
        assertThat(info.partial().confirmed()).containsExactly("제1조 계약 기간은 3개월이에요.");
        assertThat(info.partial().unknown()).containsExactly("실제 교육비");
        assertThat(info.references()).contains(new ChatReference(ReferenceType.LAW_DB, "근로기준법 제20조"));
    }

    @Test
    void OUT_OF_SCOPE는_앱_사용법만_참고로_두고_조항_버튼은_버린다() {
        when(analysisQuery.findForChat(any(), any())).thenReturn(Optional.empty());
        gptReturns(gptAnswer("OUT_OF_SCOPE", "이 앱은 근로계약서와 임대차계약서를 점검해요.", List.of("L1"),
                List.of("APP_GUIDE", "MY_DOCUMENT"), false,
                new ChatGptResponse.Partial(List.of("x"), List.of("y")),
                List.of(new ChatGptResponse.Action("OPEN_CLAUSE", "c7"),
                        new ChatGptResponse.Action("OPEN_DOCUMENT_TYPE", ""))));

        ChatAnswerInfo info = pipeline.ask(USER, new AskCommand(null, "보험 약관도 봐 줘", "S01", null, null, null, null));

        assertThat(info.answerType()).isEqualTo(AnswerType.OUT_OF_SCOPE);
        assertThat(info.linkedDocument()).isNull();
        assertThat(info.references()).containsExactly(new ChatReference(ReferenceType.APP_GUIDE, "앱 사용법"));
        assertThat(info.partial()).isNull();
        assertThat(info.actions()).extracting(a -> a.type()).containsExactly(ActionType.OPEN_DOCUMENT_TYPE);
        assertThat(savedConversations.get(0).getContractId()).isNull();
        assertThat(text(sentRequest())).contains("[연결된 계약서] 없음 (일반 질문)");
    }

    @Test
    void 단정_표현_문장과_내부_키_ID_표현을_사용자_글에서_감춘다() {
        gptReturns(gptAnswer("ANSWERED",
                "c7 조항은 무효예요. c7은 퇴사하면 교육비를 내게 해요 (L1). 제공된 근거로 보면 확인이 필요해요. "
                        + "서명해도 괜찮아요. <script>alert(1)</script><i>기울임</i>",
                List.of("L1"), List.of("MY_DOCUMENT"), true, null,
                List.of(new ChatGptResponse.Action("OPEN_CLAUSE", "c99"),
                        new ChatGptResponse.Action("OPEN_SCREEN", "S09"),
                        new ChatGptResponse.Action("OPEN_SCREEN", "S12"))));

        ChatAnswerInfo info = pipeline.ask(USER, newQuestion("교육비 조항 괜찮아?", null));

        assertThat(info.answer())
                .doesNotContain("무효", "서명해도", "c7", "(L1)", "제공된 근거", "<script>", "<i>")
                .contains("제7조는 퇴사하면 교육비를 내게 해요.", "관련 근거로 보면 확인이 필요해요.");
        // 이 분석에 없는 조항(c99), 버튼으로 열 수 없는 화면(S09), 비교가 없는데 비교 화면(S12)은 버린다
        assertThat(info.actions()).isEmpty();
        assertThat(info.answerType()).isEqualTo(AnswerType.ANSWERED);
    }

    @Test
    void 답이_다_지워지면_대신_쓰는_안내와_PARTIAL() {
        gptReturns(gptAnswer("ANSWERED", "이 계약은 불법이에요.", List.of(), List.of(), false, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, newQuestion("이 계약 불법이야?", null));

        assertThat(info.answer()).isEqualTo(ChatAnswerValidator.FALLBACK_ANSWER);
        assertThat(info.answerType()).isEqualTo(AnswerType.PARTIAL);
        assertThat(info.partial().unknown()).isNotEmpty();
    }

    @Test
    void 질문_낱말로_찾은_조문도_근거로_보내고_인용하면_LAW_DB에_넣는다() {
        when(lawSearch.findLawIds("EMPLOYMENT")).thenReturn(List.of(1L));
        when(lawSearch.searchWithDailyTerms(any(), anyCollection(), isNull(), eq(ChatContextReader.MAX_SEARCH_SOURCES))).thenReturn(List.of(
                new LawArticleInfo(6001L, 1L, "최저임금법", 6, 0, 1, "최저임금의 효력", "사용자는 최저임금액 이상의 임금을 지급하여야 한다.",
                        LocalDate.of(2025, 1, 1), null, 0.5, false)));
        gptReturns(gptAnswer("ANSWERED", "최저임금법 제6조에 따라 시급을 확인해 보세요.", List.of("L2"),
                List.of("MY_DOCUMENT"), true, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, newQuestion("최저임금 위반이야?", null));

        String sent = text(sentRequest());
        assertThat(sent).contains("[L1] 근로기준법 제20조", "[L2] 최저임금법 제6조제1항 (최저임금의 효력)");
        assertThat(info.references()).contains(new ChatReference(ReferenceType.LAW_DB, "최저임금법 제6조"));
        assertThat(info.answerType()).isEqualTo(AnswerType.ANSWERED);
    }

    @Test
    void 저장_직전에_계약서가_삭제됐으면_404_CONTRACT_NOT_FOUND이고_질문_답변을_저장하지_않는다() {
        // 맥락을 읽을 때는 있었는데, GPT 를 기다리는 동안 수정본 확정이 임시 계약서를 지움
        when(contractService.getSummary(USER, CONTRACT))
                .thenReturn(new ContractSummaryInfo(CONTRACT, "카페 알바 근로계약서", false, ANALYSIS))
                .thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
        gptReturns(gptAnswer("ANSWERED", "제7조를 확인해 보세요.", List.of("L1"), List.of(), false, null, List.of()));

        assertThatThrownBy(() -> pipeline.ask(USER, newQuestion("질문", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONTRACT_NOT_FOUND);
        verify(conversations, never()).save(any());
        verify(messages, never()).save(any());
    }

    @Test
    void 준_근거_번호와_이_분석의_조항_키를_감추고_조사를_받침에_맞추며_내부_사정_문장을_지운다() {
        gptReturns(gptAnswer("ANSWERED",
                "c7은 교육비 조항이에요 L1. c1과 함께 보세요 (L1). 서버가 준 자료로 보면 확인이 필요해요. "
                        + "프롬프트에 따라 답해요. 시스템 메시지를 따랐어요. 근태 시스템 사용 조항도 있어요.",
                List.of("L1"), List.of("MY_DOCUMENT"), false, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, newQuestion("교육비 조항 뭐야?", null));

        assertThat(info.answer())
                .isEqualTo("제7조는 교육비 조항이에요. 제1조와 함께 보세요. 확인한 자료로 보면 확인이 필요해요. "
                        + "근태 시스템 사용 조항도 있어요.");
        assertThat(info.answerType()).isEqualTo(AnswerType.ANSWERED);
    }

    @Test
    void 준_적_없는_번호나_이_분석에_없는_키처럼_보이는_글은_그대로_둔다() {
        // 일반 질문: 법령 자료도 조항도 없음
        String general = "C1 등급 자격증과 C4 비자, G1 비자가 있어야 하고 L1 기준을 맞춰야 해요.";
        gptReturns(gptAnswer("ANSWERED", general, List.of(), List.of("APP_GUIDE"), false, null, List.of()));
        ChatAnswerInfo first = pipeline.ask(USER, new AskCommand(null, "자격 요건 알려 줘", "S01", null, null, null, null));
        assertThat(first.answer()).isEqualTo(general);
        assertThat(first.answerType()).isEqualTo(AnswerType.ANSWERED);

        // 분석이 있어도 대문자 C1 과 이 분석에 없는 c99 는 키가 아니다 (c1 은 이 분석에 있음)
        String withAnalysis = "C1 등급 자격증이 필요하고 c99도 봐야 해요.";
        gptReturns(gptAnswer("ANSWERED", withAnalysis, List.of(), List.of("MY_DOCUMENT"), false, null, List.of()));
        assertThat(pipeline.ask(USER, newQuestion("자격 요건은?", null)).answer()).isEqualTo(withAnalysis);
    }

    @Test
    void 준_근거_번호도_괄호_없이_조사나_다른_글자가_붙으면_그대로_둔다() {
        // L1 을 준 상태 (분석 근거)
        String[] kept = {"L1 기준을 충족해요.", "G1등급이에요.", "L1-2 규격이에요.", "근거는 L1, G1이에요.", "L1과 같이 봐요."};
        for (String a : kept) {
            gptReturns(gptAnswer("ANSWERED", a, List.of(), List.of("MY_DOCUMENT"), false, null, List.of()));
            assertThat(pipeline.ask(USER, newQuestion("질문", null)).answer()).as(a).isEqualTo(a);
        }
        // 문장 끝·쉼표 앞의 준 번호는 지움
        gptReturns(gptAnswer("ANSWERED", "교육비 조항을 봐야 해요 L1. 계약 기간 L1, 함께 봐요.", List.of("L1"),
                List.of("MY_DOCUMENT"), false, null, List.of()));
        assertThat(pipeline.ask(USER, newQuestion("질문", null)).answer()).isEqualTo("교육비 조항을 봐야 해요. 계약 기간, 함께 봐요.");
        gptReturns(gptAnswer("ANSWERED", "근거: L1\n다음 줄이에요.", List.of("L1"), List.of("MY_DOCUMENT"), false, null,
                List.of()));
        assertThat(pipeline.ask(USER, newQuestion("질문", null)).answer()).isEqualTo("근거:\n다음 줄이에요.");
    }

    @Test
    void 계약_내용에_흔한_내부_규칙_주어진_지시는_지우지_않는다() {
        String text = "회사 내부 규칙에 따라 휴가를 정한다고 적혀 있어요. "
                + "회사로부터 주어진 지시에 따라 업무를 한다고 되어 있어요.";
        gptReturns(gptAnswer("ANSWERED", text, List.of(), List.of("MY_DOCUMENT"), false, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, newQuestion("휴가는 어떻게 정해?", null));

        assertThat(info.answer()).isEqualTo(text);
        assertThat(info.answerType()).isEqualTo(AnswerType.ANSWERED);
    }

    @Test
    void 날짜와_소수점은_문장_끝으로_보지_않아_문장을_지운_뒤에도_깨지지_않는다() {
        gptReturns(gptAnswer("ANSWERED", "계약 시작일은 2026.11.12이고 인상률은 10.5%예요. 이 계약은 무효예요.",
                List.of(), List.of("MY_DOCUMENT"), false, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, newQuestion("시작일 언제야?", null));

        assertThat(info.answer()).isEqualTo("계약 시작일은 2026.11.12이고 인상률은 10.5%예요.");
    }

    @Test
    void 흔한_낱말이_든_정상_답변은_ANSWERED를_유지한다() {
        String[] answers = {
                "다른 방법으로 다시 찍어 보세요.",
                "앱 사용법에 따라 가리면 돼요.",
                "계산법으로 보면 시급이 맞아요.",
                "일정이 어긋나지 않게 확인해 보세요.",
                "계약을 위반하면 위약금을 낸다고 적혀 있어요.",
                "법률 상담을 받아 보세요.",
                "촬영법에 따라 다시 찍어 보세요.",
                "대처법에 따라 상대에게 물어보세요.",
                "법적 도움이 필요하면 노동청에 문의해 보세요.",
        };
        for (String a : answers) {
            gptReturns(gptAnswer("ANSWERED", a, List.of(), List.of("APP_GUIDE"), false, null, List.of()));
            ChatAnswerInfo info = pipeline.ask(USER, new AskCommand(null, "질문", "S01", null, null, null, null));
            assertThat(info.answerType()).as(a).isEqualTo(AnswerType.ANSWERED);
            assertThat(info.answer()).as(a).isEqualTo(a);
            assertThat(info.partial()).as(a).isNull();
        }
    }

    @Test
    void 근거_없는_진짜_법적_판단은_PARTIAL() {
        String[] answers = {"법에 어긋날 수 있어요.", "근로기준법 위반일 수 있어요.", "근로기준법 위반이에요.",
                "법적으로 문제가 될 수 있어요.",
                "근로기준법상 문제가 될 수 있어요.", "민법상 보증금은 돌려받을 수 있어요.",
                "주택임대차보호법상 2년 미만 계약도 2년으로 봐요.", "노동법상 문제가 있어 보여요.",
                "주택임대차보호법에서도 보장하는 권리예요.", "근로기준법에도 어긋나요.",
                "근로기준법으로는 허용되지 않아요.", "근로기준법과도 맞지 않아요."};
        for (String a : answers) {
            gptReturns(gptAnswer("ANSWERED", a, List.of(), List.of("APP_GUIDE"), false, null, List.of()));
            ChatAnswerInfo info = pipeline.ask(USER, new AskCommand(null, "질문", "S01", null, null, null, null));
            assertThat(info.answerType()).as(a).isEqualTo(AnswerType.PARTIAL);
            assertThat(info.partial().unknown()).as(a).isNotEmpty();
        }
    }

    @Test
    void 남은_인용이_없는데_법적_판단_말이_있으면_mentionsLaw와_상관없이_PARTIAL() {
        gptReturns(gptAnswer("ANSWERED", "이 조항은 법에 어긋날 수 있어요.", List.of("L9"), List.of("MY_DOCUMENT"),
                false, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, newQuestion("교육비 조항 괜찮아?", null));

        assertThat(info.answerType()).isEqualTo(AnswerType.PARTIAL);
        assertThat(info.answer()).isEqualTo("이 조항은 법에 어긋날 수 있어요.");
        assertThat(info.partial().unknown()).contains(ChatAnswerValidator.NO_LAW_UNKNOWN);
        assertThat(info.references()).extracting(ChatReference::type).doesNotContain(ReferenceType.LAW_DB);
    }

    @Test
    void 방법에_같은_말은_법적_판단으로_보지_않는다() {
        gptReturns(gptAnswer("ANSWERED", "사진을 올리는 방법에 대해 알려 드릴게요.", List.of(), List.of("APP_GUIDE"),
                false, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, new AskCommand(null, "사진 어떻게 올려?", "S01", null, null, null, null));

        assertThat(info.answerType()).isEqualTo(AnswerType.ANSWERED);
        assertThat(info.partial()).isNull();
    }

    @Test
    void 완료된_분석이_없으면_partial_confirmed를_비운다() {
        gptReturns(gptAnswer("PARTIAL", "계약서 내용은 아직 알 수 없어요.", List.of(), List.of(), false,
                new ChatGptResponse.Partial(List.of("시급은 10,000원이에요."), List.of("계약서 내용")), List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, new AskCommand(null, "내 시급 얼마야?", "S01", null, null, null, null));

        assertThat(info.answerType()).isEqualTo(AnswerType.PARTIAL);
        assertThat(info.partial().confirmed()).isEmpty();
        assertThat(info.partial().unknown()).containsExactly("계약서 내용");
    }

    // ---- 대화 이어 가기 ----

    @Test
    void 기존_대화는_최근_대화와_요약을_보내고_요약은_조건부로_바꾼다() {
        Conversation conv = Conversation.create(USER, CONTRACT, ANALYSIS, null, "이전 요약: 교육비를 물었다.");
        ReflectionTestUtils.setField(conv, "conversationId", 3L);
        when(conversations.findByConversationIdAndUserId(3L, USER)).thenReturn(Optional.of(conv));
        ChatMessage q = ChatMessage.question(3L, "교육비 조항 괜찮아?", "S09", null);
        ChatMessage a = ChatMessage.answer(3L, "확인이 필요해요.", AnswerType.ANSWERED, List.of(), null, List.of());
        when(messages.findByConversationIdOrderByMessageIdDesc(eq(3L), any(Pageable.class))).thenReturn(List.of(a, q));
        when(conversations.updateSummaryIfUnchanged(eq(3L), any(), any(), any())).thenReturn(1);
        gptReturns(gptAnswer("ANSWERED", "제7조를 먼저 보세요.", List.of(), List.of("MY_DOCUMENT"), false, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, new AskCommand(3L, "그럼 뭐부터 물어봐?", "S10", null, 999L, null, 999L));

        assertThat(info.newConversation()).isFalse();
        assertThat(info.conversationId()).isEqualTo(3L);
        assertThat(savedConversations).isEmpty();
        String sent = text(sentRequest());
        assertThat(sent).contains("이전 요약: 교육비를 물었다.", "사용자: 교육비 조항 괜찮아?\n도우미: 확인이 필요해요.",
                "[질문]\n그럼 뭐부터 물어봐?");
        verify(conversations).updateSummaryIfUnchanged(eq(3L), eq("이전 요약: 교육비를 물었다."),
                eq("사용자는 교육비 반환 조항을 물었다."), any());
        verify(conversations, never()).touch(anyLong(), any());
        // 기존 대화에서는 analysisId·comparisonId 를 무시한다 (확인도 하지 않음)
        verify(analysisQuery, never()).findForChat(USER, 999L);
        verifyNoInteractions(comparisonService);
        // ai_call_log 에 대화 ID
        ArgumentCaptor<GptContext> ctx = ArgumentCaptor.forClass(GptContext.class);
        verify(gpt).call(any(), any(), any(), ctx.capture());
        assertThat(ctx.getValue().conversationId()).isEqualTo(3L);
        assertThat(ctx.getValue().userId()).isEqualTo(USER);
    }

    @Test
    void 동시에_온_다른_질문이_요약을_먼저_바꿨으면_요약은_쓰지_않고_수정_시각만() {
        Conversation conv = Conversation.create(USER, null, null, null, null);
        ReflectionTestUtils.setField(conv, "conversationId", 3L);
        when(conversations.findByConversationIdAndUserId(3L, USER)).thenReturn(Optional.of(conv));
        when(messages.findByConversationIdOrderByMessageIdDesc(eq(3L), any(Pageable.class))).thenReturn(List.of());
        when(conversations.updateSummaryIfUnchanged(eq(3L), isNull(), any(), any())).thenReturn(0);
        gptReturns(gptAnswer("ANSWERED", "앱에서 사진을 찍어 올리면 돼요.", List.of(), List.of("APP_GUIDE"), false, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, new AskCommand(3L, "어떻게 써?", "S01", null, null, null, null));

        assertThat(info.linkedDocument()).isNull();
        verify(conversations).touch(eq(3L), any());
        assertThat(saved).hasSize(2);
    }

    @Test
    void 기존_대화에_다른_계약서를_보내면_409_CONVERSATION_DOCUMENT_MISMATCH() {
        Conversation linked = Conversation.create(USER, CONTRACT, null, null, null);
        ReflectionTestUtils.setField(linked, "conversationId", 3L);
        Conversation general = Conversation.create(USER, null, null, null, null);
        ReflectionTestUtils.setField(general, "conversationId", 4L);
        when(conversations.findByConversationIdAndUserId(3L, USER)).thenReturn(Optional.of(linked));
        when(conversations.findByConversationIdAndUserId(4L, USER)).thenReturn(Optional.of(general));

        for (AskCommand cmd : List.of(new AskCommand(3L, "질문", "S08", 99L, null, null, null),
                new AskCommand(4L, "질문", "S08", CONTRACT, null, null, null))) {
            assertThatThrownBy(() -> pipeline.ask(USER, cmd))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CONVERSATION_DOCUMENT_MISMATCH);
        }
        verifyNoInteractions(gpt);
        verify(messages, never()).save(any());
    }

    @Test
    void 같은_계약서를_다시_보내면_그대로_이어_간다() {
        Conversation linked = Conversation.create(USER, CONTRACT, null, null, "요약");
        ReflectionTestUtils.setField(linked, "conversationId", 3L);
        when(conversations.findByConversationIdAndUserId(3L, USER)).thenReturn(Optional.of(linked));
        when(messages.findByConversationIdOrderByMessageIdDesc(eq(3L), any(Pageable.class))).thenReturn(List.of());
        when(conversations.updateSummaryIfUnchanged(anyLong(), any(), any(), any())).thenReturn(1);
        gptReturns(gptAnswer("ANSWERED", "네, 확인해 볼게요.", List.of(), List.of(), false, null, List.of()));

        ChatAnswerInfo info = pipeline.ask(USER, new AskCommand(3L, "질문", "S08", CONTRACT, null, null, null));

        assertThat(info.linkedDocument().contractId()).isEqualTo(CONTRACT);
        // 대화에 분석이 없으면 계약서의 최신 완료 분석을 쓴다
        assertThat(text(sentRequest())).contains("[분석 결과 요약]");
    }

    // ---- 확인 실패 (GPT·저장 안 함) ----

    @Test
    void 내_대화가_아니면_404_CONVERSATION_NOT_FOUND() {
        when(conversations.findByConversationIdAndUserId(3L, USER)).thenReturn(Optional.empty());
        assertError(new AskCommand(3L, "질문", "S10", null, null, null, null), ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void 계약서가_없거나_남의_것이면_B의_CONTRACT_NOT_FOUND를_그대로() {
        when(contractService.getSummary(USER, 99L)).thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
        assertError(new AskCommand(null, "질문", "S08", 99L, null, null, null), ErrorCode.CONTRACT_NOT_FOUND);
    }

    @Test
    void 분석이_내_것이_아니거나_다른_계약서의_것이면_404_ANALYSIS_NOT_FOUND() {
        when(analysisQuery.findForChat(USER, 77L)).thenReturn(Optional.empty());
        assertError(new AskCommand(null, "질문", "S08", CONTRACT, 77L, null, null), ErrorCode.ANALYSIS_NOT_FOUND);

        AnalysisChatInfo other = new AnalysisChatInfo(66L, 55L, AnalysisStatus.COMPLETED, "EMPLOYMENT", COMPLETED_AT,
                List.of(), List.of(), List.of(), List.of());
        when(analysisQuery.findForChat(USER, 66L)).thenReturn(Optional.of(other));
        assertError(new AskCommand(null, "질문", "S08", CONTRACT, 66L, null, null), ErrorCode.ANALYSIS_NOT_FOUND);
    }

    @Test
    void 비교가_내_것이_아니거나_이전본이_다르면_404_COMPARISON_NOT_FOUND() {
        // 내 것이 아니면 B getForChat 이 COMPARISON_NOT_FOUND 를 던진다
        when(comparisonService.getForChat(USER, 9L)).thenThrow(new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));
        assertError(new AskCommand(null, "질문", "S12", CONTRACT, null, null, 9L), ErrorCode.COMPARISON_NOT_FOUND);

        when(comparisonService.getForChat(USER, 10L))
                .thenReturn(new ComparisonForChatInfo(10L, 55L, "PENDING_CONFIRM", null, List.of()));
        assertError(new AskCommand(null, "질문", "S12", CONTRACT, null, null, 10L), ErrorCode.COMPARISON_NOT_FOUND);
    }

    @Test
    void 계약서_없이_온_비교의_이전본_계약서가_삭제됐으면_B_getForChat의_404_COMPARISON_NOT_FOUND를_그대로() {
        // 이전본 계약서가 삭제됐으면 B getForChat 이 COMPARISON_NOT_FOUND 를 던진다. 이전본 계약서는 읽지 않는다
        when(comparisonService.getForChat(USER, 11L)).thenThrow(new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));

        assertError(new AskCommand(null, "질문", "S12", null, null, null, 11L), ErrorCode.COMPARISON_NOT_FOUND);
        verify(contractService, never()).getSummary(any(), anyLong());
    }

    @Test
    void 새_대화_저장은_계약서를_잠근_뒤_다시_확인하고_저장한다() {
        gptReturns(gptAnswer("ANSWERED", "답", List.of(), List.of(), false, null, List.of()));

        pipeline.ask(USER, newQuestion("질문", null));

        var order = inOrder(contractService, conversations);
        order.verify(contractService).lockForUpdate(CONTRACT);
        order.verify(contractService).getSummary(USER, CONTRACT);
        order.verify(conversations).save(any());
    }

    @Test
    void GPT를_기다리는_동안_계약서가_삭제되면_잠금에서_CONTRACT_NOT_FOUND_이고_아무것도_저장하지_않는다() {
        gptReturns(gptAnswer("ANSWERED", "답", List.of(), List.of(), false, null, List.of()));
        doThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND))
                .when(contractService).lockForUpdate(CONTRACT);

        assertThatThrownBy(() -> pipeline.ask(USER, newQuestion("질문", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONTRACT_NOT_FOUND);
        verify(conversations, never()).save(any());
        verify(messages, never()).save(any());
    }

    @Test
    void 기존_대화_저장은_계약서를_잠그지_않는다() {
        Conversation conversation = Conversation.create(USER, CONTRACT, ANALYSIS, null, "요약");
        ReflectionTestUtils.setField(conversation, "conversationId", 3L);
        when(conversations.findByConversationIdAndUserId(3L, USER)).thenReturn(Optional.of(conversation));
        gptReturns(gptAnswer("ANSWERED", "답", List.of(), List.of(), false, null, List.of()));

        pipeline.ask(USER, new AskCommand(3L, "질문", "S10", null, null, null, null));

        verify(contractService, never()).lockForUpdate(anyLong());
    }

    @Test
    void 분석에_없는_조항이면_404_CLAUSE_NOT_FOUND() {
        assertError(newQuestion("질문", 999L), ErrorCode.CLAUSE_NOT_FOUND);
    }

    @Test
    void 짧은_시간에_질문이_너무_많으면_429_RATE_LIMITED() {
        ChatPipeline limited = pipeline(1);
        gptReturns(gptAnswer("ANSWERED", "답", List.of(), List.of(), false, null, List.of()));
        limited.ask(USER, new AskCommand(null, "질문", "S01", null, null, null, null));

        assertThatThrownBy(() -> limited.ask(USER, new AskCommand(null, "질문", "S01", null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void GPT가_실패하면_에러가_그대로_나가고_질문도_저장하지_않는다() {
        when(gpt.call(any(), any(), any(), any())).thenThrow(new GptCallException(GptFailureReason.TIMEOUT, "시간 초과", 2));

        assertThatThrownBy(() -> pipeline.ask(USER, newQuestion("질문", null)))
                .isInstanceOf(GptCallException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_TIMEOUT);
        verify(conversations, never()).save(any());
        verify(messages, never()).save(any());
    }

    // ---- 분석 상태·비교 맥락 ----

    @Test
    void 분석_중인_분석만_있으면_내용_없이_답하고_내_문서_참고는_빼고_분석_결과_버튼도_버린다() {
        when(contractService.getSummary(USER, CONTRACT))
                .thenReturn(new ContractSummaryInfo(CONTRACT, null, false, null));
        AnalysisChatInfo running = new AnalysisChatInfo(90L, CONTRACT, AnalysisStatus.ANALYZING, "EMPLOYMENT", null,
                List.of(), List.of(), List.of(), List.of());
        when(analysisQuery.findForChat(USER, 90L)).thenReturn(Optional.of(running));
        gptReturns(gptAnswer("PARTIAL", "분석이 끝나면 알려 드릴게요.", List.of(), List.of("MY_DOCUMENT", "APP_GUIDE"),
                false, new ChatGptResponse.Partial(List.of(), List.of("분석이 끝나야 알 수 있어요.")),
                List.of(new ChatGptResponse.Action("OPEN_SCREEN", "S08"))));

        ChatAnswerInfo info = pipeline.ask(USER, new AskCommand(null, "결과 언제 나와?", "S07", CONTRACT, 90L, null, null));

        assertThat(text(sentRequest())).contains("지금 분석 중이다", "완료된 분석 결과가 없다").doesNotContain("[분석 결과 요약]");
        assertThat(info.references()).containsExactly(new ChatReference(ReferenceType.APP_GUIDE, "앱 사용법"));
        assertThat(info.actions()).isEmpty();
        assertThat(info.linkedDocument().analyzedAt()).isNull();
        assertThat(savedConversations.get(0).getAnalysisId()).isEqualTo(90L);
        assertThat(info.answerType()).isEqualTo(AnswerType.PARTIAL);
        assertThat(info.partial().unknown()).containsExactly("분석이 끝나야 알 수 있어요.");
    }

    @Test
    void 비교에서_질문하면_비교를_연결하고_바뀐_곳을_보내며_비교_결과_버튼을_준다() {
        when(comparisonService.getForChat(USER, 9L)).thenReturn(new ComparisonForChatInfo(9L, CONTRACT, "PENDING_CONFIRM",
                "보증금 그대로", List.of(new Change("CHANGED", "AMOUNT", "월세", "월 55만 원", "월 58만 원", "매달 3만 원 늘어요"))));
        gptReturns(gptAnswer("ANSWERED", "월세가 3만 원 올랐어요.", List.of(), List.of("CURRENT_SCREEN"), false, null,
                List.of(new ChatGptResponse.Action("OPEN_SCREEN", "S12"))));

        ChatAnswerInfo info = pipeline.ask(USER, new AskCommand(null, "뭐가 바뀌었어?", "S12", null, null, null, 9L));

        assertThat(savedConversations.get(0).getComparisonId()).isEqualTo(9L);
        assertThat(savedConversations.get(0).getContractId()).isEqualTo(CONTRACT);
        assertThat(text(sentRequest())).contains("[비교 결과] 상태: PENDING_CONFIRM", "월세 | 이전: 월 55만 원 | 수정: 월 58만 원",
                "그대로인 곳: 보증금 그대로");
        assertThat(info.references()).containsExactly(new ChatReference(ReferenceType.CURRENT_SCREEN, "비교 결과"));
        assertThat(info.actions().get(0).params()).containsEntry("screen", "S12").containsEntry("comparisonId", 9L);
    }

    @Test
    void 비교가_있는_기존_대화는_B_getForChat의_그대로인_곳과_바뀐_곳을_비교_결과로_보낸다() {
        Conversation conversation = Conversation.create(USER, CONTRACT, null, 9L, "요약");
        ReflectionTestUtils.setField(conversation, "conversationId", 3L);
        when(conversations.findByConversationIdAndUserId(3L, USER)).thenReturn(Optional.of(conversation));
        when(comparisonService.getForChat(USER, 9L)).thenReturn(new ComparisonForChatInfo(9L, CONTRACT, "CONFIRMED",
                "근무 시간 그대로", List.of(
                        new Change("CHANGED", "AMOUNT", "시급", "10,000원", "10,500원", "시간당 500원 늘어요"),
                        new Change("ADDED", "CLAUSE", "수습 기간", null, "3개월", null))));
        gptReturns(gptAnswer("ANSWERED", "시급이 올랐어요.", List.of(), List.of("CURRENT_SCREEN"), false, null, List.of()));

        pipeline.ask(USER, new AskCommand(3L, "뭐가 바뀌었어?", "S12", null, null, null, null));

        assertThat(text(sentRequest())).contains("[비교 결과] 상태: CONFIRMED", "시급 | 이전: 10,000원 | 수정: 10,500원",
                "수습 기간", "그대로인 곳: 근무 시간 그대로");
        verify(comparisonService).getForChat(USER, 9L);
    }

    @Test
    void 비교가_있는_기존_대화에서_B_getForChat이_COMPARISON_NOT_FOUND면_404이고_아무것도_저장하지_않는다() {
        Conversation conversation = Conversation.create(USER, CONTRACT, null, 9L, "요약");
        ReflectionTestUtils.setField(conversation, "conversationId", 3L);
        when(conversations.findByConversationIdAndUserId(3L, USER)).thenReturn(Optional.of(conversation));
        when(comparisonService.getForChat(USER, 9L)).thenThrow(new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));

        assertError(new AskCommand(3L, "뭐가 바뀌었어?", "S12", null, null, null, null), ErrorCode.COMPARISON_NOT_FOUND);
    }

    void assertError(AskCommand command, ErrorCode expected) {
        assertThatThrownBy(() -> pipeline.ask(USER, command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
        verifyNoInteractions(gpt);
        verify(messages, never()).save(any());
        verify(conversations, never()).save(any());
    }
}
