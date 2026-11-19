package com.team.docai.domain.chat.service;

import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.service.query.AnalysisChatQueryService;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.auth.TokenService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 질문 중 계약서가 동시에 삭제된 경우를 실제 DB 트랜잭션으로 확인한다.
 * getSummary(B, 실제 메서드)는 통과하고 그 뒤 getVersionOwner(B)가 CONTRACT_NOT_FOUND 를 던지게 한다
 * (B 파일은 고치지 않고 이 테스트 안의 spy 로만 바꿔 끼움). 응답은 500(UnexpectedRollbackException)이 아니라 명세 코드여야 한다.
 * <p>
 * 사용자·문서 종류·계약서·버전·분석·대화를 이 테스트가 직접 INSERT 하고 끝나면 그 ID 로만 지운다. GPT 는 부르지 않는다(가짜).
 */
@SpringBootTest(properties = "docai.chat.requests-per-minute=100")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class ChatOwnershipRaceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokenService;
    @Autowired AnalysisChatQueryService analysisChatQueryService;
    @Autowired PlatformTransactionManager txManager;
    @MockitoSpyBean ContractService contractService;
    @MockitoBean GptClient gptClient;

    final String typeCode = "TO_" + Long.toString(System.nanoTime(), 36).toUpperCase();
    UUID owner;
    long contract;
    long version;
    long analysis;
    long conversation;

    @BeforeEach
    void setUp() {
        owner = UUID.randomUUID();
        jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                owner, String.format("%064x", System.nanoTime()));
        jdbc.update("insert into document_type (code, name, category, situation, description, check_level, supported,"
                + " popular, sort_order) values (?, '테스트 계약서', 'WORK', '테스트', '테스트', 'A', true, false, 999)", typeCode);
        contract = jdbc.queryForObject("insert into contract (user_id, document_type_code, title, archived,"
                + " current_version_no, created_at, updated_at) values (?, ?, '테스트 계약서', false, 1, now(), now())"
                + " returning contract_id", Long.class, owner, typeCode);
        version = jdbc.queryForObject("insert into contract_version (contract_id, version_no, page_count, created_at,"
                + " updated_at) values (?, 1, 1, now(), now()) returning version_id", Long.class, contract);
        analysis = jdbc.queryForObject("insert into analysis (version_id, document_type_code, status, progress_step,"
                + " prompt_version, started_at, completed_at) values (?, ?, 'COMPLETED', 'DONE', 'test', now(), now())"
                + " returning analysis_id", Long.class, version, typeCode);
        jdbc.update("update contract_version set latest_analysis_id = ? where version_id = ?", analysis, version);
        conversation = jdbc.queryForObject("insert into conversation (user_id, contract_id, analysis_id, created_at,"
                + " updated_at) values (?, ?, ?, now(), now()) returning conversation_id", Long.class,
                owner, contract, analysis);

        // getSummary 확인 뒤 계약서가 삭제된 것처럼: B 의 주인 확인만 실패
        doThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND))
                .when(contractService).getVersionOwner(any(), anyLong());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from chat_message where conversation_id in"
                + " (select conversation_id from conversation where user_id = ?)", owner);
        jdbc.update("delete from conversation where user_id = ?", owner);
        jdbc.update("update contract_version set latest_analysis_id = null where version_id = ?", version);
        jdbc.update("delete from analysis where analysis_id = ?", analysis);
        jdbc.update("delete from contract_version where version_id = ?", version);
        jdbc.update("delete from contract where contract_id = ?", contract);
        jdbc.update("delete from document_type where code = ?", typeCode);
        jdbc.update("delete from users where user_id = ?", owner);
    }

    @Test
    void 새_대화에서_계약서의_최신_분석을_읽다_삭제되면_404_CONTRACT_NOT_FOUND() throws Exception {
        ask("{\"message\":\"질문\",\"screen\":\"S08\",\"contractId\":" + contract + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
        assertNothingSaved(1);
    }

    @Test
    void 기존_대화의_분석을_읽다_삭제되면_404_CONTRACT_NOT_FOUND() throws Exception {
        ask("{\"conversationId\":" + conversation + ",\"message\":\"질문\",\"screen\":\"S10\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
        assertNothingSaved(1);
    }

    @Test
    void 새_대화의_analysisId가_볼_수_없는_분석이면_404_ANALYSIS_NOT_FOUND() throws Exception {
        ask("{\"message\":\"질문\",\"screen\":\"S08\",\"analysisId\":" + analysis + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        assertNothingSaved(1);
    }

    @Test
    void B_예외를_삼키고_이어_가면_커밋에서_UnexpectedRollbackException이_나므로_삼키면_안_된다() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            try {
                analysisChatQueryService.findForChat(owner, analysis);
            } catch (BusinessException e) {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONTRACT_NOT_FOUND);
            }
            assertThat(s.isRollbackOnly()).isTrue(); // B 프록시가 바깥 트랜잭션을 rollback-only 로 표시
        })).isInstanceOf(UnexpectedRollbackException.class);
    }

    ResultActions ask(String body) throws Exception {
        return mvc.perform(post("/api/chats/messages").header("Authorization", "Bearer " + tokenService.issue(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    void assertNothingSaved(long conversations) {
        verify(gptClient, never()).call(any(), any(), any(), any());
        assertThat(jdbc.queryForObject("select count(*) from conversation where user_id = ?", Long.class, owner))
                .isEqualTo(conversations);
        assertThat(jdbc.queryForObject("select count(*) from chat_message m join conversation c"
                + " on c.conversation_id = m.conversation_id where c.user_id = ?", Long.class, owner)).isZero();
    }
}
