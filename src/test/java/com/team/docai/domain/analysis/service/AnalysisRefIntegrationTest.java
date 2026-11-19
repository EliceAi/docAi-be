package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRefInfo;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A1 getRef 와 분석 결과 조회의 주인 확인을 실제 DB(V2 스키마)와 B {@code ContractService.getVersionOwner} 로 확인한다.
 * <ul>
 *   <li>사용자·문서 종류·계약서·버전·쪽·분석을 이 테스트가 직접 INSERT 하고 끝나면 그 ID 로만 지운다.</li>
 *   <li>getRef 는 B 의 API 처럼 바깥 읽기 전용 트랜잭션 안에서 부른다 (연결 메서드는 호출한 쪽 트랜잭션 안에서).</li>
 *   <li>B 메서드가 불릴 때 실제 트랜잭션이 있고 읽기 전용이며, 그 트랜잭션을 시작한 것이 부르는 쪽(A 의 서비스 또는 B 의 바깥
 *       트랜잭션)인지 트랜잭션 이름으로 확인한다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class AnalysisRefIntegrationTest {

    static final String OUTER_TX = "B-schedule-candidates-test";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokenService;
    @Autowired AnalysisService analysisService;
    @Autowired PlatformTransactionManager txManager;
    @MockitoSpyBean ContractService contractService;

    final String typeCode = "TR_" + Long.toString(System.nanoTime(), 36).toUpperCase();
    final List<UUID> users = new ArrayList<>();
    final List<Long> contracts = new ArrayList<>();
    final List<Long> versions = new ArrayList<>();
    final List<Long> analyses = new ArrayList<>();
    final List<String> txSeen = new ArrayList<>();

    UUID owner;
    UUID other;
    long myContract;
    long myVersion;
    long done;
    long running;
    long othersAnalysis;
    long deletedAnalysis;

    @BeforeEach
    void setUp() {
        owner = newUser();
        other = newUser();
        jdbc.update("insert into document_type (code, name, category, situation, description, check_level, supported,"
                + " popular, sort_order) values (?, '테스트 계약서', 'WORK', '테스트', '테스트', 'A', true, false, 999)", typeCode);

        myContract = newContract(owner, false);
        myVersion = newVersion(myContract);
        newPage(myVersion, 1, 2);
        newPage(myVersion, 2, 3);
        done = newAnalysis(myVersion, "COMPLETED", "now()");
        running = newAnalysis(myVersion, "ANALYZING", null);
        othersAnalysis = newAnalysis(newVersion(newContract(other, false)), "COMPLETED", "now()");
        deletedAnalysis = newAnalysis(newVersion(newContract(owner, true)), "COMPLETED", "now()");

        // B 메서드가 불릴 때의 트랜잭션 상태를 기록하고 실제 메서드를 부른다
        doAnswer(inv -> {
            txSeen.add(TransactionSynchronizationManager.isActualTransactionActive() + ":"
                    + TransactionSynchronizationManager.isCurrentTransactionReadOnly() + ":"
                    + TransactionSynchronizationManager.getCurrentTransactionName());
            return inv.callRealMethod();
        }).when(contractService).getVersionOwner(any(), anyLong());
    }

    @AfterEach
    void cleanUp() {
        for (Long a : analyses) {
            jdbc.update("delete from analysis where analysis_id = ?", a);
        }
        for (Long v : versions) {
            jdbc.update("delete from contract_page where version_id = ?", v);
            jdbc.update("delete from contract_version where version_id = ?", v);
        }
        for (Long c : contracts) {
            jdbc.update("delete from contract where contract_id = ?", c);
        }
        jdbc.update("delete from document_type where code = ?", typeCode);
        for (UUID u : users) {
            jdbc.update("delete from users where user_id = ?", u);
        }
    }

    @Test
    void getRef_내_분석이면_분석_버전_계약서_상태_완료시각을_준다() {
        AnalysisRefInfo ref = inReadOnlyTx(() -> analysisService.getRef(owner, done));

        OffsetDateTime completedAt = jdbc.queryForObject(
                "select completed_at from analysis where analysis_id = ?", OffsetDateTime.class, done);
        assertThat(ref.analysisId()).isEqualTo(done);
        assertThat(ref.versionId()).isEqualTo(myVersion);
        assertThat(ref.contractId()).isEqualTo(myContract);
        assertThat(ref.status()).isEqualTo("COMPLETED");
        assertThat(ref.completedAt()).isNotNull().isEqualTo(completedAt);
        // B 메서드는 부르는 쪽(B 의 바깥 읽기 전용 트랜잭션) 안에서 불렸다
        assertThat(txSeen).containsExactly("true:true:" + OUTER_TX);
    }

    @Test
    void getRef_완료되지_않은_분석은_상태만_담는다() {
        AnalysisRefInfo ref = inReadOnlyTx(() -> analysisService.getRef(owner, running));

        assertThat(ref.status()).isEqualTo("ANALYZING");
        assertThat(ref.completedAt()).isNull();
        assertThat(ref.contractId()).isEqualTo(myContract);
    }

    @Test
    void getRef_없거나_남의_것이거나_삭제된_계약서의_분석이면_ANALYSIS_NOT_FOUND() {
        for (long id : new long[]{othersAnalysis, deletedAnalysis, Long.MAX_VALUE}) {
            // 바깥 트랜잭션이 rollback-only 가 돼도 원래 예외가 그대로 올라온다 (UnexpectedRollbackException 아님)
            assertThatThrownBy(() -> inReadOnlyTx(() -> analysisService.getRef(owner, id)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_FOUND));
        }
        // 남의 사람 쪽에서 보면 내 분석도 남의 것
        assertThatThrownBy(() -> inReadOnlyTx(() -> analysisService.getRef(other, done)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_FOUND));
    }

    @Test
    void getRef_바깥_트랜잭션_없이_불러도_AnalysisService_의_읽기_전용_트랜잭션_안에서_B를_부른다() {
        analysisService.getRef(owner, done);

        assertThat(txSeen).containsExactly("true:true:" + AnalysisService.class.getName() + ".getRef");
    }

    @Test
    void 결과_조회는_B_getVersionOwner_로_주인을_확인하고_그_값으로_계약서_버전_쪽_가린_곳을_채운다() throws Exception {
        mvc.perform(get("/api/analyses/" + done).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").value(myContract))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.result.title").value("테스트 계약서"))
                .andExpect(jsonPath("$.data.result.coverage.pageCount").value(2))
                .andExpect(jsonPath("$.data.result.coverage.maskedCount").value(5));

        assertThat(txSeen).containsExactly("true:true:"
                + "com.team.docai.domain.analysis.service.query.AnalysisQueryService.getResult");
    }

    @Test
    void 결과_조회는_남의_것이거나_삭제된_계약서의_분석이면_404_ANALYSIS_NOT_FOUND() throws Exception {
        for (long id : new long[]{othersAnalysis, deletedAnalysis}) {
            mvc.perform(get("/api/analyses/" + id).header("Authorization", bearer(owner)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        }
        mvc.perform(get("/api/analyses/" + done).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        assertThat(txSeen).hasSize(3).allMatch(s -> s.startsWith("true:true:"));
    }

    // ---- 도우미 ----

    <T> T inReadOnlyTx(java.util.function.Supplier<T> body) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);
        tx.setName(OUTER_TX);
        return tx.execute(s -> body.get());
    }

    String bearer(UUID user) {
        return "Bearer " + tokenService.issue(user);
    }

    UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                id, String.format("%064x", System.nanoTime()));
        users.add(id);
        return id;
    }

    long newContract(UUID user, boolean deleted) {
        long id = jdbc.queryForObject("insert into contract (user_id, document_type_code, title, archived,"
                + " current_version_no, deleted_at, created_at, updated_at)"
                + " values (?, ?, '테스트 계약서', false, 1, " + (deleted ? "now()" : "null") + ", now(), now())"
                + " returning contract_id", Long.class, user, typeCode);
        contracts.add(id);
        return id;
    }

    long newVersion(long contractId) {
        long id = jdbc.queryForObject("insert into contract_version (contract_id, version_no, page_count, created_at,"
                + " updated_at) values (?, 1, 2, now(), now()) returning version_id", Long.class, contractId);
        versions.add(id);
        return id;
    }

    void newPage(long versionId, int pageNo, int masked) {
        jdbc.update("insert into contract_page (version_id, page_no, image_key, masked_count) values (?, ?, ?, ?)",
                versionId, pageNo, "test/" + versionId + "/" + pageNo + ".jpg", masked);
    }

    long newAnalysis(long versionId, String status, String completedSql) {
        long id = jdbc.queryForObject("insert into analysis (version_id, document_type_code, status, progress_step,"
                + " prompt_version, started_at, completed_at)"
                + " values (?, ?, ?, ?, 'test', now(), " + (completedSql == null ? "null" : completedSql) + ")"
                + " returning analysis_id", Long.class, versionId, typeCode, status,
                "COMPLETED".equals(status) ? "DONE" : "READING_TEXT");
        analyses.add(id);
        return id;
    }
}
