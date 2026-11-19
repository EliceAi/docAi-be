package com.team.docai.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptFailureReason;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.domain.chat.dto.ChatGptResponse;
import com.team.docai.domain.chat.repository.ConversationRepository;
import com.team.docai.domain.chat.service.ChatService;
import com.team.docai.global.auth.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 질문·대화 기록 조회·moveContract 를 실제 DB(V2 스키마, ddl-auto validate)로 확인한다. GPT 는 가짜 (GptClient 를 바꿔 끼움).
 * <ul>
 *   <li>사용자·문서 종류·계약서·버전·분석·조항·근거·법령·비교를 이 테스트가 직접 INSERT 하고, 끝나면 그 ID(와 이 테스트 사용자의
 *       대화)로만 지운다.</li>
 *   <li>저장(대화·질문·답변 JSONB), 201/200, 요약 조건부 갱신, GPT 를 트랜잭션 밖에서 부르는지, 명세 에러 코드 표의 행마다.</li>
 * </ul>
 */
@SpringBootTest(properties = "docai.chat.requests-per-minute=100")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class ChatIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokenService;
    @Autowired ChatService chatService;
    @Autowired ConversationRepository conversationRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean GptClient gptClient;

    final String suffix = Long.toString(System.nanoTime(), 36).toUpperCase();
    final String typeCode = "TC_" + suffix;
    final String lawName = "챗봇테스트법" + suffix;

    final List<UUID> users = new ArrayList<>();
    final List<Long> contracts = new ArrayList<>();
    final List<Long> versions = new ArrayList<>();
    final List<Long> analyses = new ArrayList<>();
    final List<Long> comparisons = new ArrayList<>();
    Long lawId;
    Long lawArticleId;

    UUID owner;
    UUID other;
    long contract;      // 내 보관 계약서 (분석 완료)
    long version;
    long analysis;
    long clauseTerm;
    long clausePenalty;
    long otherDoc;      // 내 다른 계약서
    long deleted;       // 삭제된 내 계약서
    long revised;       // 수정본 임시 계약서
    long othersContract;
    long othersAnalysis;
    long comparison;
    long othersComparison;

    final AtomicBoolean gptInTransaction = new AtomicBoolean(false);
    final AtomicReference<String> nextSummary = new AtomicReference<>("요약 1");

    @BeforeEach
    void setUp() {
        owner = newUser();
        other = newUser();
        jdbc.update("insert into document_type (code, name, category, situation, description, check_level, supported,"
                + " popular, sort_order) values (?, '테스트 계약서', 'WORK', '테스트', '테스트', 'A', true, false, 999)", typeCode);
        lawId = jdbc.queryForObject("insert into law (name, name_norm, law_kind, source_law_id, synced_at)"
                + " values (?, ?, '법률', 'T', now()) returning law_id", Long.class, lawName, lawName);
        lawArticleId = jdbc.queryForObject("insert into law_article (law_id, article_no, article_branch_no, title, content,"
                + " source_mst, effective_from) values (?, 20, 0, '위약 예정의 금지',"
                + " '사용자는 근로계약 불이행에 대한 위약금을 예정하는 계약을 체결하지 못한다.', 'T', '2000-01-01')"
                + " returning law_article_id", Long.class, lawId);

        contract = newContract(owner, true, false, "카페 알바 근로계약서");
        version = newVersion(contract);
        analysis = newAnalysis(version);
        jdbc.update("update contract_version set latest_analysis_id = ? where version_id = ?", analysis, version);
        clauseTerm = newClause(analysis, "c1", "제1조", "계약 기간", "OK");
        clausePenalty = newClause(analysis, "c7", "제7조", "교육비 반환", "NEEDS_CHECK");
        jdbc.update("insert into analysis_clause_ref (clause_id, ref_label, law_article_id, found_by, cited, ref_title, ref_text)"
                        + " values (?, 'L1', ?, 'TAG_MAP', true, ?, '사용자는 위약금을 예정하는 계약을 체결하지 못한다.')",
                clausePenalty, lawArticleId, lawName + " 제20조");

        otherDoc = newContract(owner, false, false, "다른 계약서");
        newVersion(otherDoc);
        deleted = newContract(owner, false, true, "삭제된 계약서");
        newVersion(deleted);
        revised = newContract(owner, false, false, null);
        long revisedVersion = newVersion(revised);
        long revisedAnalysis = newAnalysis(revisedVersion);
        comparison = newComparison(owner, contract, version, revised, revisedVersion, revisedAnalysis);

        othersContract = newContract(other, true, false, "남의 계약서");
        long othersVersion = newVersion(othersContract);
        othersAnalysis = newAnalysis(othersVersion);
        long othersRevised = newContract(other, false, false, null);
        long othersRevisedVersion = newVersion(othersRevised);
        othersComparison = newComparison(other, othersContract, othersVersion, othersRevised, othersRevisedVersion,
                newAnalysis(othersRevisedVersion));

        when(gptClient.call(eq(GptPurpose.CHAT), any(GptRequest.class), eq(ChatGptResponse.class), any(GptContext.class)))
                .thenAnswer(inv -> {
                    gptInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new ChatGptResponse("ANSWERED",
                            "c7은 1년 안에 퇴사하면 교육비를 물게 해요 [L1]. " + lawName + " 제20조와 함께 확인이 필요해요.",
                            List.of("L1"), List.of("MY_DOCUMENT"), true,
                            new ChatGptResponse.Partial(List.of(), List.of()),
                            List.of(new ChatGptResponse.Action("OPEN_CLAUSE", "c7")), nextSummary.get());
                });
    }

    @AfterEach
    void cleanUp() {
        for (UUID u : users) {
            jdbc.update("delete from chat_message where conversation_id in"
                    + " (select conversation_id from conversation where user_id = ?)", u);
            jdbc.update("delete from conversation where user_id = ?", u);
        }
        for (Long c : comparisons) {
            jdbc.update("delete from comparison where comparison_id = ?", c);
        }
        for (Long v : versions) {
            jdbc.update("update contract_version set latest_analysis_id = null where version_id = ?", v);
        }
        for (Long a : analyses) {
            jdbc.update("delete from analysis_clause_ref where clause_id in"
                    + " (select clause_id from analysis_clause where analysis_id = ?)", a);
            jdbc.update("delete from analysis_clause where analysis_id = ?", a);
            jdbc.update("delete from analysis where analysis_id = ?", a);
        }
        for (Long v : versions) {
            jdbc.update("delete from contract_version where version_id = ?", v);
        }
        for (Long c : contracts) {
            jdbc.update("delete from contract where contract_id = ?", c);
        }
        if (lawArticleId != null) {
            jdbc.update("delete from law_article where law_article_id = ?", lawArticleId);
        }
        if (lawId != null) {
            jdbc.update("delete from law where law_id = ?", lawId);
        }
        jdbc.update("delete from document_type where code = ?", typeCode);
        for (UUID u : users) {
            jdbc.update("delete from users where user_id = ?", u);
        }
    }

    @Test
    void 새_대화는_201로_저장하고_이어서_질문하면_200과_요약을_바꾸고_기록으로_다시_본다() throws Exception {
        JsonNode first = body(ask(owner, "{\"message\":\"교육비 조항 괜찮아?\",\"screen\":\"S09\",\"contractId\":" + contract
                + ",\"analysisId\":" + analysis + ",\"clauseId\":" + clausePenalty + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.answerType").value("ANSWERED"))
                .andExpect(jsonPath("$.data.answer").value("제7조는 1년 안에 퇴사하면 교육비를 물게 해요. "
                        + lawName + " 제20조와 함께 확인이 필요해요."))
                .andExpect(jsonPath("$.data.linkedDocument.contractId").value(contract))
                .andExpect(jsonPath("$.data.linkedDocument.title").value("카페 알바 근로계약서"))
                .andExpect(jsonPath("$.data.references[0].type").value("MY_DOCUMENT"))
                .andExpect(jsonPath("$.data.references[1].type").value("LAW_DB"))
                .andExpect(jsonPath("$.data.references[1].label").value(lawName + " 제20조"))
                .andExpect(jsonPath("$.data.partial").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.actions[0].type").value("OPEN_CLAUSE"))
                .andExpect(jsonPath("$.data.actions[0].params.analysisId").value(analysis))
                .andExpect(jsonPath("$.data.actions[0].params.clauseId").value(clausePenalty)));
        assertThat(gptInTransaction).isFalse();
        assertThat(first.at("/data/linkedDocument/analyzedAt").asText()).endsWith("+09:00");

        long conversationId = first.at("/data/conversationId").asLong();
        Map<String, Object> conv = jdbc.queryForMap("select user_id, contract_id, analysis_id, comparison_id, summary"
                + " from conversation where conversation_id = ?", conversationId);
        assertThat(conv.get("user_id")).isEqualTo(owner);
        assertThat(((Number) conv.get("contract_id")).longValue()).isEqualTo(contract);
        assertThat(((Number) conv.get("analysis_id")).longValue()).isEqualTo(analysis);
        assertThat(conv.get("comparison_id")).isNull();
        assertThat(conv.get("summary")).isEqualTo("요약 1");
        List<Map<String, Object>> rows = jdbc.queryForList("select message_id, role, screen, answer_type,"
                + " refs_json::text as refs, partial_json::text as partial, actions_json::text as actions"
                + " from chat_message where conversation_id = ? order by message_id", conversationId);
        assertThat(rows).extracting(r -> r.get("role")).containsExactly("USER", "ASSISTANT");
        assertThat(rows.get(0).get("screen")).isEqualTo("S09");
        assertThat(objectMapper.readTree((String) rows.get(0).get("refs")).get("clauseId").asLong()).isEqualTo(clausePenalty);
        assertThat(rows.get(1).get("answer_type")).isEqualTo("ANSWERED");
        assertThat(rows.get(1).get("partial")).isNull();
        assertThat(objectMapper.readTree((String) rows.get(1).get("refs")).get("references").get(1).get("label").asText())
                .isEqualTo(lawName + " 제20조");
        assertThat(objectMapper.readTree((String) rows.get(1).get("actions")).get(0).get("params").get("clauseId").asLong())
                .isEqualTo(clausePenalty);
        assertThat(((Number) rows.get(1).get("message_id")).longValue()).isEqualTo(first.at("/data/messageId").asLong());

        // 이어서 질문: 같은 계약서를 다시 보내도 됨, 200, 요약은 읽은 요약("요약 1")이 그대로일 때만 바뀜
        nextSummary.set("요약 2");
        ask(owner, "{\"conversationId\":" + conversationId + ",\"message\":\"그럼 뭐부터 물어봐?\",\"screen\":\"S10\","
                + "\"contractId\":" + contract + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.conversationId").value(conversationId));
        assertThat(jdbc.queryForObject("select summary from conversation where conversation_id = ?", String.class,
                conversationId)).isEqualTo("요약 2");

        // 대화 기록: 오래된 순 4개, USER 에는 답변 칸 없음
        history(owner, "/api/chats/" + conversationId + "/messages")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(conversationId))
                .andExpect(jsonPath("$.data.linkedDocument.contractId").value(contract))
                .andExpect(jsonPath("$.data.comparisonId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.messages.length()").value(4))
                .andExpect(jsonPath("$.data.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.data.messages[0].content").value("교육비 조항 괜찮아?"))
                .andExpect(jsonPath("$.data.messages[0].answerType").doesNotExist())
                .andExpect(jsonPath("$.data.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[1].answerType").value("ANSWERED"))
                .andExpect(jsonPath("$.data.messages[1].references[1].label").value(lawName + " 제20조"))
                .andExpect(jsonPath("$.data.messages[1].partial").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.messages[1].actions[0].params.clauseId").value(clausePenalty))
                .andExpect(jsonPath("$.data.messages[2].content").value("그럼 뭐부터 물어봐?"));

        // 더 보기: 최근 1개, 그 앞 1개
        JsonNode page = body(history(owner, "/api/chats/" + conversationId + "/messages?size=1")
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.messages.length()").value(1))
                .andExpect(jsonPath("$.data.messages[0].role").value("ASSISTANT")));
        long last = page.at("/data/messages/0/messageId").asLong();
        history(owner, "/api/chats/" + conversationId + "/messages?size=1&beforeMessageId=" + last)
                .andExpect(jsonPath("$.data.messages[0].content").value("그럼 뭐부터 물어봐?"))
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }

    @Test
    void 요약은_읽을_때_본_값이_그대로일_때만_바뀐다() {
        long id = jdbc.queryForObject("insert into conversation (user_id, summary, created_at, updated_at)"
                + " values (?, '처음', now(), now()) returning conversation_id", Long.class, owner);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Integer stale = tx.execute(s -> conversationRepository.updateSummaryIfUnchanged(id, "다른 값", "새 요약", OffsetDateTime.now()));
        Integer nullExpected = tx.execute(s -> conversationRepository.updateSummaryIfUnchanged(id, null, "새 요약", OffsetDateTime.now()));
        assertThat(stale).isZero();
        assertThat(nullExpected).isZero();
        assertThat(jdbc.queryForObject("select summary from conversation where conversation_id = ?", String.class, id))
                .isEqualTo("처음");

        Integer ok = tx.execute(s -> conversationRepository.updateSummaryIfUnchanged(id, "처음", "새 요약", OffsetDateTime.now()));
        assertThat(ok).isEqualTo(1);
        long empty = jdbc.queryForObject("insert into conversation (user_id, created_at, updated_at)"
                + " values (?, now(), now()) returning conversation_id", Long.class, owner);
        Integer first = tx.execute(s -> conversationRepository.updateSummaryIfUnchanged(empty, null, "첫 요약", OffsetDateTime.now()));
        assertThat(first).isEqualTo(1);
    }

    @Test
    void 같은_요약을_읽은_두_질문이_동시에_요약을_바꾸면_먼저_커밋한_것만_남는다() throws Exception {
        long id = jdbc.queryForObject("insert into conversation (user_id, summary, created_at, updated_at)"
                + " values (?, '처음', now(), now()) returning conversation_id", Long.class, owner);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstUpdated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 첫 번째: 바꾸고 커밋하지 않은 채 행 잠금을 쥐고 기다림
            Future<Integer> first = pool.submit(() -> tx.execute(s -> {
                int n = conversationRepository.updateSummaryIfUnchanged(id, "처음", "요약 A", OffsetDateTime.now());
                firstUpdated.countDown();
                try {
                    releaseFirst.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return n;
            }));
            assertThat(firstUpdated.await(10, TimeUnit.SECONDS)).isTrue();
            // 두 번째: 같은 '처음' 을 보고 바꾸려다 행 잠금에서 기다림
            Future<Integer> second = pool.submit(() -> tx.execute(s ->
                    conversationRepository.updateSummaryIfUnchanged(id, "처음", "요약 B", OffsetDateTime.now())));
            Thread.sleep(500);
            assertThat(second.isDone()).as("두 번째 UPDATE 는 첫 번째 커밋까지 기다린다").isFalse();
            releaseFirst.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            // 커밋 뒤 다시 본 행의 요약이 '처음' 이 아니므로 0행 (덮어쓰지 않음)
            assertThat(second.get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select summary from conversation where conversation_id = ?", String.class, id))
                .isEqualTo("요약 A");
    }

    @Test
    void 비교에서_질문하면_비교와_이전본을_연결하고_기록에_comparisonId가_나온다() throws Exception {
        JsonNode res = body(ask(owner, "{\"message\":\"뭐가 바뀌었어?\",\"screen\":\"S12\",\"comparisonId\":" + comparison + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.linkedDocument.contractId").value(contract)));
        long conversationId = res.at("/data/conversationId").asLong();
        assertThat(jdbc.queryForObject("select comparison_id from conversation where conversation_id = ?", Long.class,
                conversationId)).isEqualTo(comparison);
        history(owner, "/api/chats/" + conversationId + "/messages")
                .andExpect(jsonPath("$.data.comparisonId").value(comparison));
    }

    @Test
    void 명세_에러_코드_행마다_확인하고_실패하면_아무것도_저장하지_않는다() throws Exception {
        long conversationId = body(ask(owner, "{\"message\":\"질문\",\"screen\":\"S08\",\"contractId\":" + contract + "}")
                .andExpect(status().isCreated())).at("/data/conversationId").asLong();
        long othersConversation = jdbc.queryForObject("insert into conversation (user_id, created_at, updated_at)"
                + " values (?, now(), now()) returning conversation_id", Long.class, other);
        long ownerMessagesBefore = ownerMessages();

        // 401
        mvc.perform(post("/api/chats/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"질문\",\"screen\":\"S08\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        // 400
        ask(owner, "{\"message\":\"\",\"screen\":\"S08\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        // 404 CONTRACT_NOT_FOUND: 남의 것, 삭제됨, 없음
        for (long id : new long[]{othersContract, deleted, Long.MAX_VALUE}) {
            ask(owner, "{\"message\":\"질문\",\"screen\":\"S08\",\"contractId\":" + id + "}")
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
        }
        // 404 CONVERSATION_NOT_FOUND: 남의 대화
        ask(owner, "{\"conversationId\":" + othersConversation + ",\"message\":\"질문\",\"screen\":\"S10\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
        // 409 CONVERSATION_DOCUMENT_MISMATCH
        ask(owner, "{\"conversationId\":" + conversationId + ",\"message\":\"질문\",\"screen\":\"S08\",\"contractId\":"
                + otherDoc + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONVERSATION_DOCUMENT_MISMATCH"));
        // 404 ANALYSIS_NOT_FOUND: 남의 분석, 다른 계약서의 분석
        ask(owner, "{\"message\":\"질문\",\"screen\":\"S08\",\"analysisId\":" + othersAnalysis + "}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        ask(owner, "{\"message\":\"질문\",\"screen\":\"S08\",\"contractId\":" + otherDoc + ",\"analysisId\":" + analysis + "}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        // 404 COMPARISON_NOT_FOUND: 남의 비교
        ask(owner, "{\"message\":\"질문\",\"screen\":\"S12\",\"comparisonId\":" + othersComparison + "}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMPARISON_NOT_FOUND"));
        // 404 CLAUSE_NOT_FOUND: 다른 분석의 조항
        long othersClause = newClause(othersAnalysis, "c1", "제1조", "남의 조항", "OK");
        ask(owner, "{\"message\":\"질문\",\"screen\":\"S09\",\"contractId\":" + contract + ",\"clauseId\":" + othersClause + "}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAUSE_NOT_FOUND"));
        // 503 AI_REQUEST_FAILED: 질문을 저장하지 않음
        when(gptClient.call(any(), any(), any(), any()))
                .thenThrow(new GptCallException(GptFailureReason.SERVER_ERROR, "HTTP 500", 2));
        ask(owner, "{\"message\":\"질문\",\"screen\":\"S08\",\"contractId\":" + contract + "}")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_REQUEST_FAILED"));

        assertThat(ownerMessages()).isEqualTo(ownerMessagesBefore);
        assertThat(jdbc.queryForObject("select count(*) from conversation where user_id = ?", Long.class, owner)).isEqualTo(1);

        // 대화 기록 조회: 401, 남의 대화 404
        mvc.perform(get("/api/chats/" + conversationId + "/messages"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        history(owner, "/api/chats/" + othersConversation + "/messages")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void 연결된_계약서가_삭제되면_기록의_linkedDocument는_null() throws Exception {
        long conversationId = jdbc.queryForObject("insert into conversation (user_id, contract_id, created_at, updated_at)"
                + " values (?, ?, now(), now()) returning conversation_id", Long.class, owner, deleted);
        history(owner, "/api/chats/" + conversationId + "/messages")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkedDocument").value(org.hamcrest.Matchers.nullValue()));
        // 그 대화에 질문하면 CONTRACT_NOT_FOUND (삭제된 계약서는 없는 것)
        ask(owner, "{\"conversationId\":" + conversationId + ",\"message\":\"질문\",\"screen\":\"S10\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
    }

    @Test
    void moveContract는_확정_트랜잭션_안에서만_임시_계약서의_대화를_이전_계약서로_옮긴다() {
        long moved = jdbc.queryForObject("insert into conversation (user_id, contract_id, comparison_id, created_at, updated_at)"
                + " values (?, ?, ?, now(), now()) returning conversation_id", Long.class, owner, revised, comparison);
        long untouched = jdbc.queryForObject("insert into conversation (user_id, contract_id, created_at, updated_at)"
                + " values (?, ?, now(), now()) returning conversation_id", Long.class, owner, otherDoc);

        assertThatThrownBy(() -> chatService.moveContract(revised, contract))
                .isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> chatService.moveContract(revised, contract));

        assertThat(jdbc.queryForObject("select contract_id from conversation where conversation_id = ?", Long.class, moved))
                .isEqualTo(contract);
        assertThat(jdbc.queryForObject("select comparison_id from conversation where conversation_id = ?", Long.class, moved))
                .isEqualTo(comparison);
        assertThat(jdbc.queryForObject("select contract_id from conversation where conversation_id = ?", Long.class, untouched))
                .isEqualTo(otherDoc);
    }

    // ---- 도우미 ----

    ResultActions ask(UUID user, String body) throws Exception {
        return mvc.perform(post("/api/chats/messages").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions history(UUID user, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", bearer(user)));
    }

    JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    String bearer(UUID user) {
        return "Bearer " + tokenService.issue(user);
    }

    long ownerMessages() {
        return jdbc.queryForObject("select count(*) from chat_message m join conversation c"
                + " on c.conversation_id = m.conversation_id where c.user_id = ?", Long.class, owner);
    }

    UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                id, String.format("%064x", System.nanoTime()));
        users.add(id);
        return id;
    }

    long newContract(UUID user, boolean archived, boolean deleted, String title) {
        long id = jdbc.queryForObject("insert into contract (user_id, document_type_code, title, archived,"
                + " current_version_no, deleted_at, created_at, updated_at)"
                + " values (?, ?, ?, ?, 1, " + (deleted ? "now()" : "null") + ", now(), now()) returning contract_id",
                Long.class, user, typeCode, title, archived);
        contracts.add(id);
        return id;
    }

    long newVersion(long contractId) {
        long id = jdbc.queryForObject("insert into contract_version (contract_id, version_no, page_count, created_at,"
                + " updated_at) values (?, 1, 2, now(), now()) returning version_id", Long.class, contractId);
        versions.add(id);
        return id;
    }

    long newAnalysis(long versionId) {
        long id = jdbc.queryForObject("insert into analysis (version_id, document_type_code, status, progress_step,"
                + " prompt_version, started_at, completed_at) values (?, ?, 'COMPLETED', 'DONE', 'test', now(), now())"
                + " returning analysis_id", Long.class, versionId, typeCode);
        analyses.add(id);
        return id;
    }

    long newClause(long analysisId, String key, String articleNo, String title, String verdict) {
        return jdbc.queryForObject("insert into analysis_clause (analysis_id, clause_key, article_no, title,"
                        + " original_text, page_no, issue_found, verdict, basis_type, headline, why_check)"
                        + " values (?, ?, ?, ?, '근로자가 1년 이내에 퇴사하면 교육비 30만 원을 반환한다.', 1, ?, ?, ?, ?, ?)"
                        + " returning clause_id", Long.class,
                analysisId, key, articleNo, title, !"OK".equals(verdict), verdict,
                "OK".equals(verdict) ? null : "AI_INTERPRETATION",
                "OK".equals(verdict) ? null : "1년 안에 퇴사하면 교육비 반환",
                "OK".equals(verdict) ? null : "계약 기간이 3개월이라 사실상 교육비를 내야 해요.");
    }

    long newComparison(UUID user, long previousContract, long previousVersion, long revisedContract, long revisedVersion,
                       long revisedAnalysis) {
        long id = jdbc.queryForObject("insert into comparison (user_id, previous_contract_id, previous_version_id,"
                        + " revised_contract_id, revised_version_id, revised_analysis_id, compatibility, status,"
                        + " unchanged_summary, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, 'FULL', 'PENDING_CONFIRM', '보증금 그대로', now(), now())"
                        + " returning comparison_id", Long.class,
                user, previousContract, previousVersion, revisedContract, revisedVersion, revisedAnalysis);
        comparisons.add(id);
        return id;
    }
}
