package com.team.docai.web.chat;

import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptFailureReason;
import com.team.docai.domain.chat.dto.AskCommand;
import com.team.docai.domain.chat.dto.ChatAnswerInfo;
import com.team.docai.domain.chat.dto.ChatHistoryInfo;
import com.team.docai.domain.chat.dto.LinkedDocumentInfo;
import com.team.docai.domain.chat.entity.ActionType;
import com.team.docai.domain.chat.entity.AnswerType;
import com.team.docai.domain.chat.entity.ChatAction;
import com.team.docai.domain.chat.entity.ChatPartial;
import com.team.docai.domain.chat.entity.ChatReference;
import com.team.docai.domain.chat.entity.ChatRole;
import com.team.docai.domain.chat.entity.ReferenceType;
import com.team.docai.domain.chat.service.ChatPipeline;
import com.team.docai.domain.chat.service.ChatService;
import com.team.docai.global.auth.AnonymousRateLimiter;
import com.team.docai.global.auth.TokenService;
import com.team.docai.global.config.SecurityConfig;
import com.team.docai.global.config.TokenConfig;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 질문·대화 기록 조회 API 의 HTTP 모양 (경로, 인증, 요청 검증, 상태 코드, 응답 필드, 명세 에러 코드 표의 행마다).
 * 서비스는 가짜.
 */
@WebMvcTest(ChatController.class)
@Import({SecurityConfig.class, TokenConfig.class, TokenService.class, AnonymousRateLimiter.class})
class ChatControllerTest {

    static final String ASK_URL = "/api/chats/messages";
    static final String BODY = "{\"conversationId\":null,\"message\":\"내 계약 문제없어?\",\"screen\":\"S08\","
            + "\"contractId\":12,\"analysisId\":88,\"clauseId\":null,\"comparisonId\":null}";
    static final AskCommand COMMAND = new AskCommand(null, "내 계약 문제없어?", "S08", 12L, 88L, null, null);
    static final OffsetDateTime ANALYZED_AT = OffsetDateTime.parse("2026-11-12T14:20:00+09:00");

    @Autowired MockMvc mvc;
    @Autowired TokenService tokenService;
    @MockitoBean ChatPipeline pipeline;
    @MockitoBean ChatService chatService;

    final UUID userId = UUID.randomUUID();

    ResultActions ask(String body) throws Exception {
        return mvc.perform(post(ASK_URL).header("Authorization", "Bearer " + tokenService.issue(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions history(String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + tokenService.issue(userId)));
    }

    static ChatAnswerInfo answer(boolean newConversation, AnswerType type, ChatPartial partial) {
        Map<String, Object> clauseParams = new LinkedHashMap<>();
        clauseParams.put("analysisId", 88L);
        clauseParams.put("clauseId", 707L);
        Map<String, Object> screenParams = new LinkedHashMap<>();
        screenParams.put("screen", "S13");
        screenParams.put("analysisId", 88L);
        return new ChatAnswerInfo(3L, 41L, newConversation, type, "두 가지를 먼저 보세요.",
                new LinkedDocumentInfo(12L, "카페 알바 근로계약서", ANALYZED_AT),
                List.of(new ChatReference(ReferenceType.MY_DOCUMENT, "카페 알바 근로계약서"),
                        new ChatReference(ReferenceType.LAW_DB, "근로기준법 제20조")),
                partial,
                List.of(new ChatAction(ActionType.OPEN_CLAUSE, "근거 조항 보기 (제7조)", clauseParams),
                        new ChatAction(ActionType.OPEN_SCREEN, "일정 등록 화면으로", screenParams)));
    }

    // ---- AI 질문 ----

    @Test
    void 새_대화면_201과_명세_응답_필드를_준다() throws Exception {
        when(pipeline.ask(userId, COMMAND)).thenReturn(answer(true, AnswerType.ANSWERED, null));

        ask(BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.data.conversationId").value(3))
                .andExpect(jsonPath("$.data.messageId").value(41))
                .andExpect(jsonPath("$.data.answerType").value("ANSWERED"))
                .andExpect(jsonPath("$.data.answer").value("두 가지를 먼저 보세요."))
                .andExpect(jsonPath("$.data.linkedDocument.contractId").value(12))
                .andExpect(jsonPath("$.data.linkedDocument.title").value("카페 알바 근로계약서"))
                .andExpect(jsonPath("$.data.linkedDocument.analyzedAt").value("2026-11-12T14:20:00+09:00"))
                .andExpect(jsonPath("$.data.references[0].type").value("MY_DOCUMENT"))
                .andExpect(jsonPath("$.data.references[0].label").value("카페 알바 근로계약서"))
                .andExpect(jsonPath("$.data.references[1].type").value("LAW_DB"))
                .andExpect(jsonPath("$.data.references[1].label").value("근로기준법 제20조"))
                .andExpect(jsonPath("$.data.partial").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.actions[0].type").value("OPEN_CLAUSE"))
                .andExpect(jsonPath("$.data.actions[0].label").value("근거 조항 보기 (제7조)"))
                .andExpect(jsonPath("$.data.actions[0].params.analysisId").value(88))
                .andExpect(jsonPath("$.data.actions[0].params.clauseId").value(707))
                .andExpect(jsonPath("$.data.actions[1].type").value("OPEN_SCREEN"))
                .andExpect(jsonPath("$.data.actions[1].params.screen").value("S13"))
                .andExpect(jsonPath("$.data.actions[1].params.analysisId").value(88));
    }

    @Test
    void 기존_대화에_이어서_질문하면_200() throws Exception {
        AskCommand cmd = new AskCommand(3L, "교육비 조항은요?", "S10", null, null, null, null);
        when(pipeline.ask(userId, cmd)).thenReturn(answer(false, AnswerType.ANSWERED, null));

        ask("{\"conversationId\":3,\"message\":\"교육비 조항은요?\",\"screen\":\"S10\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.conversationId").value(3));
    }

    @Test
    void PARTIAL이면_partial에_confirmed와_unknown을_준다() throws Exception {
        when(pipeline.ask(any(), any())).thenReturn(answer(true, AnswerType.PARTIAL,
                new ChatPartial(List.of("계약 기간은 3개월이에요."), List.of("실제 교육비는 알 수 없어요."))));

        ask(BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.answerType").value("PARTIAL"))
                .andExpect(jsonPath("$.data.partial.confirmed[0]").value("계약 기간은 3개월이에요."))
                .andExpect(jsonPath("$.data.partial.unknown[0]").value("실제 교육비는 알 수 없어요."));
    }

    @Test
    void 일반_질문이면_linkedDocument는_null() throws Exception {
        when(pipeline.ask(any(), any())).thenReturn(new ChatAnswerInfo(4L, 50L, true, AnswerType.OUT_OF_SCOPE,
                "이 앱은 근로계약서와 임대차계약서를 점검해요.", null,
                List.of(new ChatReference(ReferenceType.APP_GUIDE, "앱 사용법")), null,
                List.of(new ChatAction(ActionType.OPEN_DOCUMENT_TYPE, "문서 종류 고르기", Map.of()))));

        ask("{\"message\":\"오늘 날씨 어때?\",\"screen\":\"S01\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.answerType").value("OUT_OF_SCOPE"))
                .andExpect(jsonPath("$.data.linkedDocument").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.references[0].type").value("APP_GUIDE"))
                .andExpect(jsonPath("$.data.actions[0].type").value("OPEN_DOCUMENT_TYPE"));
    }

    @Test
    void 질문은_앞뒤_공백을_지우고_넘긴다() throws Exception {
        AskCommand cmd = new AskCommand(null, "내 계약 문제없어?", "S08", null, null, null, null);
        when(pipeline.ask(userId, cmd)).thenReturn(answer(true, AnswerType.ANSWERED, null));

        ask("{\"message\":\"  내 계약 문제없어?  \",\"screen\":\"S08\"}").andExpect(status().isCreated());
        verify(pipeline).ask(userId, cmd);
    }

    @Test
    void 토큰이_없거나_잘못되면_401_UNAUTHORIZED() throws Exception {
        mvc.perform(post(ASK_URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post(ASK_URL).header("Authorization", "Bearer not-a-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(pipeline, never()).ask(any(), any());
    }

    @Test
    void 필수값이_없거나_형식이_틀리면_400_INVALID_INPUT() throws Exception {
        String[] bodies = {
                "{\"screen\":\"S08\"}",                                          // message 없음
                "{\"message\":\"   \",\"screen\":\"S08\"}",                      // message 공백
                "{\"message\":\"\\u3000\",\"screen\":\"S08\"}",                  // 전각 공백만
                "{\"message\":\"" + "가".repeat(1001) + "\",\"screen\":\"S08\"}", // 1000자 초과
                "{\"message\":\"" + "😀".repeat(1001) + "\",\"screen\":\"S08\"}", // 이모지 1001자
                "{\"message\":\"질문\"}",                                         // screen 없음
                "{\"message\":\"질문\",\"screen\":\"S16\"}",                      // 없는 화면
                "{\"message\":\"질문\",\"screen\":\"S00\"}",
                "{\"message\":\"질문\",\"screen\":\"s08\"}",
                "{\"message\":\"질문\",\"screen\":\"S08\",\"contractId\":0}",      // 양수 아님
                "{\"message\":\"질문\",\"screen\":\"S08\",\"conversationId\":-1}",
                "{\"message\":\"질문\",\"screen\":\"S08\",\"analysisId\":\"abc\"}",
                "{\"message\":\"질문\",\"screen\":\"S08\",\"clauseId\":0}",
                "{\"message\":\"질문\",\"screen\":\"S08\",\"comparisonId\":0}",
                "{\"message\":\"질문\",",                                          // 깨진 JSON
                ""                                                                 // 본문 없음
        };
        for (String body : bodies) {
            ask(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verify(pipeline, never()).ask(any(), any());
    }

    @Test
    void 질문이_정확히_1000자면_통과한다() throws Exception {
        String exact = "가".repeat(1000);
        when(pipeline.ask(any(), any())).thenReturn(answer(true, AnswerType.ANSWERED, null));
        ask("{\"message\":\"" + exact + "\",\"screen\":\"S15\"}").andExpect(status().isCreated());
    }

    @Test
    void Content_Type이_JSON이_아니면_400_INVALID_INPUT() throws Exception {
        mvc.perform(post(ASK_URL).header("Authorization", "Bearer " + tokenService.issue(userId))
                        .contentType(MediaType.TEXT_PLAIN).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 명세_에러_코드를_그대로_돌려준다() throws Exception {
        Object[][] cases = {
                {ErrorCode.CONTRACT_NOT_FOUND, 404},
                {ErrorCode.CONVERSATION_NOT_FOUND, 404},
                {ErrorCode.CONVERSATION_DOCUMENT_MISMATCH, 409},
                {ErrorCode.ANALYSIS_NOT_FOUND, 404},
                {ErrorCode.COMPARISON_NOT_FOUND, 404},
                {ErrorCode.CLAUSE_NOT_FOUND, 404},
                {ErrorCode.RATE_LIMITED, 429},
        };
        for (Object[] c : cases) {
            ErrorCode code = (ErrorCode) c[0];
            doThrow(new BusinessException(code)).when(pipeline).ask(any(), any());
            ask(BODY)
                    .andExpect(status().is((int) c[1]))
                    .andExpect(jsonPath("$.status").value((int) c[1]))
                    .andExpect(jsonPath("$.code").value(code.name()))
                    .andExpect(jsonPath("$.message").value(code.getMessage()));
        }
    }

    @Test
    void AI_응답_실패는_503_AI_REQUEST_FAILED_시간_초과는_504_AI_TIMEOUT() throws Exception {
        doThrow(new GptCallException(GptFailureReason.SERVER_ERROR, "HTTP 500: 내부 원인", 2)).when(pipeline).ask(any(), any());
        ask(BODY)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("AI_REQUEST_FAILED"))
                .andExpect(jsonPath("$.message").value(ErrorCode.AI_REQUEST_FAILED.getMessage()));

        doThrow(new GptCallException(GptFailureReason.TIMEOUT, "시간 초과", 2)).when(pipeline).ask(any(), any());
        ask(BODY)
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.status").value(504))
                .andExpect(jsonPath("$.code").value("AI_TIMEOUT"));
    }

    // ---- 대화 기록 조회 ----

    static ChatHistoryInfo historyInfo(boolean hasMore) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("analysisId", 88L);
        params.put("clauseId", 707L);
        return new ChatHistoryInfo(3L, new LinkedDocumentInfo(12L, "카페 알바 근로계약서", ANALYZED_AT), null, hasMore,
                List.of(new ChatHistoryInfo.MessageInfo(40L, ChatRole.USER, "내 계약 문제없어?", null, null, null, null,
                                OffsetDateTime.parse("2026-11-12T14:25:00+09:00")),
                        new ChatHistoryInfo.MessageInfo(41L, ChatRole.ASSISTANT, "두 가지를 먼저 보세요. ...",
                                AnswerType.ANSWERED, List.of(new ChatReference(ReferenceType.LAW_DB, "근로기준법 제20조")),
                                null, List.of(new ChatAction(ActionType.OPEN_CLAUSE, "근거 조항 보기 (제7조)", params)),
                                OffsetDateTime.parse("2026-11-12T14:25:06+09:00"))));
    }

    @Test
    void 대화_기록을_명세_모양으로_준다() throws Exception {
        when(chatService.getMessages(userId, 3L, null, null)).thenReturn(historyInfo(false));

        history("/api/chats/3/messages")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.conversationId").value(3))
                .andExpect(jsonPath("$.data.linkedDocument.contractId").value(12))
                .andExpect(jsonPath("$.data.linkedDocument.title").value("카페 알바 근로계약서"))
                .andExpect(jsonPath("$.data.linkedDocument.analyzedAt").value("2026-11-12T14:20:00+09:00"))
                .andExpect(jsonPath("$.data.comparisonId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.messages[0].messageId").value(40))
                .andExpect(jsonPath("$.data.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.data.messages[0].content").value("내 계약 문제없어?"))
                .andExpect(jsonPath("$.data.messages[0].createdAt").value("2026-11-12T14:25:00+09:00"))
                // USER 메시지에는 answerType·references·partial·actions 칸이 없다
                .andExpect(jsonPath("$.data.messages[0].answerType").doesNotExist())
                .andExpect(jsonPath("$.data.messages[0].references").doesNotExist())
                .andExpect(jsonPath("$.data.messages[0].partial").doesNotExist())
                .andExpect(jsonPath("$.data.messages[0].actions").doesNotExist())
                .andExpect(jsonPath("$.data.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[1].answerType").value("ANSWERED"))
                .andExpect(jsonPath("$.data.messages[1].references[0].type").value("LAW_DB"))
                .andExpect(jsonPath("$.data.messages[1].references[0].label").value("근로기준법 제20조"))
                .andExpect(jsonPath("$.data.messages[1].partial").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.messages[1].actions[0].type").value("OPEN_CLAUSE"))
                .andExpect(jsonPath("$.data.messages[1].actions[0].params.clauseId").value(707))
                .andExpect(jsonPath("$.data.messages[1].createdAt").value("2026-11-12T14:25:06+09:00"));
    }

    @Test
    void beforeMessageId와_size를_서비스에_넘긴다() throws Exception {
        when(chatService.getMessages(userId, 3L, 40L, 10)).thenReturn(historyInfo(true));

        history("/api/chats/3/messages?beforeMessageId=40&size=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(true));
        verify(chatService).getMessages(userId, 3L, 40L, 10);
    }

    @Test
    void 대화_기록_토큰이_없으면_401_UNAUTHORIZED() throws Exception {
        mvc.perform(get("/api/chats/3/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(chatService, never()).getMessages(any(), anyLong(), any(), any());
    }

    @Test
    void 대화가_없거나_남의_것이면_404_CONVERSATION_NOT_FOUND() throws Exception {
        when(chatService.getMessages(eq(userId), eq(3L), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND));

        history("/api/chats/3/messages")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void 대화_ID나_쿼리가_숫자가_아니면_400_INVALID_INPUT() throws Exception {
        history("/api/chats/abc/messages")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        history("/api/chats/3/messages?beforeMessageId=x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
