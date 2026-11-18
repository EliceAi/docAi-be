package com.team.docai.domain.analysis.service;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 미인식 항목 입력을 실제 DB(V2 스키마)로 확인한다.
 * <ul>
 *   <li>문서 종류·사용자·계약서·버전·분석·조항·주요 조건을 이 테스트가 직접 INSERT 하고 끝나면 그 ID 로만 지운다.</li>
 *   <li>저장 내용(값, source = MANUAL, state 유지, value_json 비움), 결과 조회에 '직접 입력'으로 보이는지,
 *       조항 판정이 바뀌지 않는지, 명세 에러 코드 표의 행마다 확인한다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class ManualInputIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokenService;

    final String suffix = Long.toString(System.nanoTime(), 36).toUpperCase();
    final String typeCode = "TM_" + suffix;

    final List<UUID> users = new ArrayList<>();
    final List<Long> contracts = new ArrayList<>();
    final List<Long> versions = new ArrayList<>();
    final List<Long> analyses = new ArrayList<>();

    UUID owner;
    UUID other;
    long done;
    long running;
    long failed;
    long othersAnalysis;
    long deletedAnalysis;
    long clauseOk;
    long clauseNeedsCheck;

    @BeforeEach
    void setUp() {
        owner = newUser();
        other = newUser();
        jdbc.update("insert into document_type (code, name, category, situation, description, check_level, supported,"
                + " popular, sort_order) values (?, '테스트 계약서', 'WORK', '테스트', '테스트', 'A', true, false, 999)", typeCode);

        // 내 계약서: 완료(부분 분석) · 분석 중 · 실패 분석
        long c1 = newContract(owner, false);
        long v1 = newVersion(c1);
        done = newAnalysis(v1, "COMPLETED", "DONE", null, "now()");
        jdbc.update("update analysis set partial_pages = '[2]'::jsonb where analysis_id = ?", done);
        newChunk(done);
        clauseOk = newClause(done, "c1", "OK");
        clauseNeedsCheck = newClause(done, "c2", "NEEDS_CHECK");
        jdbc.update("insert into analysis_key_term (analysis_id, clause_id, label, value, value_json, state, source,"
                        + " evidence, sort_order) values"
                        + " (?, ?, '임금', '시급 10,000원', '{\"type\":\"HOURLY\",\"amount\":10000}'::jsonb, 'NEEDS_CHECK', 'WRITTEN', '1쪽 제4조', 1),"
                        + " (?, null, '휴일', null, null, 'UNREADABLE', null, '2쪽 흐림', 2),"
                        + " (?, null, '사업주', null, null, 'MASKED', null, null, 3),"
                        + " (?, null, '연차', null, null, 'MISSING', null, null, 4)",
                done, clauseNeedsCheck, done, done, done);

        running = newAnalysis(v1, "ANALYZING", "MATCHING_LAWS", null, null);
        failed = newAnalysis(v1, "FAILED", "READING_TEXT", "PAGE_UNREADABLE", "now()");
        for (long a : new long[]{running, failed}) {
            jdbc.update("insert into analysis_key_term (analysis_id, label, state, sort_order) values (?, '휴일', 'UNREADABLE', 1)", a);
        }

        // 남의 계약서 · 삭제된 내 계약서의 완료 분석 (둘 다 읽지 못한 휴일 칸 있음)
        othersAnalysis = newAnalysis(newVersion(newContract(other, false)), "COMPLETED", "DONE", null, "now()");
        deletedAnalysis = newAnalysis(newVersion(newContract(owner, true)), "COMPLETED", "DONE", null, "now()");
        for (long a : new long[]{othersAnalysis, deletedAnalysis}) {
            jdbc.update("insert into analysis_key_term (analysis_id, label, state, sort_order) values (?, '휴일', 'UNREADABLE', 1)", a);
        }
    }

    @AfterEach
    void cleanUp() {
        for (Long a : analyses) {
            jdbc.update("delete from analysis_key_term where analysis_id = ?", a);
            jdbc.update("delete from analysis_chunk where analysis_id = ?", a);
            jdbc.update("delete from analysis_clause where analysis_id = ?", a);
        }
        for (Long a : analyses) {
            jdbc.update("delete from analysis where analysis_id = ?", a);
        }
        for (Long v : versions) {
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
    void 읽지_못한_칸에_입력하면_직접_입력으로_저장되고_결과_조회에도_그렇게_보인다() throws Exception {
        String verdictsBefore = verdicts();

        postInput(owner, done, "{\"label\":\"휴일\",\"value\":\" 매주 일요일 \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.label").value("휴일"))
                .andExpect(jsonPath("$.data.value").value("매주 일요일"))
                .andExpect(jsonPath("$.data.state").value("UNREADABLE"))
                .andExpect(jsonPath("$.data.source").value("MANUAL"));

        Map<String, Object> row = jdbc.queryForMap("select value, value_json, state, source, evidence, sort_order"
                + " from analysis_key_term where analysis_id = ? and label = '휴일'", done);
        assertThat(row.get("value")).isEqualTo("매주 일요일");
        assertThat(row.get("value_json")).isNull();
        assertThat(row.get("state")).isEqualTo("UNREADABLE");
        assertThat(row.get("source")).isEqualTo("MANUAL");
        assertThat(row.get("evidence")).isEqualTo("2쪽 흐림");
        assertThat(row.get("sort_order")).isEqualTo(2);

        // 다른 칸과 조항 판정은 그대로 (판정을 다시 계산하지 않음)
        assertThat(jdbc.queryForObject("select source from analysis_key_term where analysis_id = ? and label = '임금'",
                String.class, done)).isEqualTo("WRITTEN");
        assertThat(verdicts()).isEqualTo(verdictsBefore);

        mvc.perform(get("/api/analyses/" + done).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.keyTerms[1].label").value("휴일"))
                .andExpect(jsonPath("$.data.result.keyTerms[1].value").value("매주 일요일"))
                .andExpect(jsonPath("$.data.result.keyTerms[1].state").value("UNREADABLE"))
                .andExpect(jsonPath("$.data.result.keyTerms[1].source").value("MANUAL"))
                .andExpect(jsonPath("$.data.result.counts.needsCheck").value(1));

        // 다시 입력하면 바뀐다 (칸은 여전히 읽지 못한 칸)
        postInput(owner, done, "{\"label\":\"휴일\",\"value\":\"매주 토요일\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value("매주 토요일"))
                .andExpect(jsonPath("$.data.state").value("UNREADABLE"));
    }

    @Test
    void 토큰이_없으면_401_UNAUTHORIZED() throws Exception {
        mvc.perform(post("/api/analyses/" + done + "/manual-inputs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"휴일\",\"value\":\"매주 일요일\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertUnchanged(done);
    }

    @Test
    void 필수값이_없거나_100자를_넘으면_400_INVALID_INPUT() throws Exception {
        for (String body : new String[]{"{\"label\":\"휴일\"}", "{\"value\":\"x\"}",
                "{\"label\":\"휴일\",\"value\":\"" + "가".repeat(101) + "\"}"}) {
            postInput(owner, done, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        assertUnchanged(done);
    }

    @Test
    void 없거나_남의_것이거나_삭제된_계약서의_분석이면_404_ANALYSIS_NOT_FOUND() throws Exception {
        for (long id : new long[]{othersAnalysis, deletedAnalysis, Long.MAX_VALUE}) {
            postInput(owner, id, "{\"label\":\"휴일\",\"value\":\"매주 일요일\"}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        }
        assertUnchanged(othersAnalysis);
        assertUnchanged(deletedAnalysis);
    }

    @Test
    void 이_분석에_없는_항목이면_404_TERM_NOT_FOUND() throws Exception {
        postInput(owner, done, "{\"label\":\"보증금\",\"value\":\"500만 원\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TERM_NOT_FOUND"));
    }

    @Test
    void 읽지_못한_칸이_아니면_409_TERM_NOT_EDITABLE() throws Exception {
        for (String label : new String[]{"임금", "사업주", "연차"}) {
            postInput(owner, done, "{\"label\":\"" + label + "\",\"value\":\"직접 넣은 값\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TERM_NOT_EDITABLE"));
        }
        assertThat(jdbc.queryForObject("select count(*) from analysis_key_term where analysis_id = ? and source = 'MANUAL'",
                Long.class, done)).isZero();
        assertThat(jdbc.queryForObject("select value from analysis_key_term where analysis_id = ? and label = '임금'",
                String.class, done)).isEqualTo("시급 10,000원");
    }

    @Test
    void 분석_중이거나_실패한_분석이면_409_ANALYSIS_NOT_COMPLETED() throws Exception {
        for (long id : new long[]{running, failed}) {
            postInput(owner, id, "{\"label\":\"휴일\",\"value\":\"매주 일요일\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"));
            assertUnchanged(id);
        }
    }

    // ---- 도우미 ----

    ResultActions postInput(UUID user, long analysisId, String body) throws Exception {
        return mvc.perform(post("/api/analyses/" + analysisId + "/manual-inputs")
                .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String bearer(UUID user) {
        return "Bearer " + tokenService.issue(user);
    }

    String verdicts() {
        return jdbc.queryForList("select clause_id || ':' || verdict || ':' || coalesce(basis_type, '') from analysis_clause"
                + " where analysis_id = ? order by clause_id", String.class, done).toString();
    }

    void assertUnchanged(long analysisId) {
        Map<String, Object> row = jdbc.queryForMap(
                "select value, source from analysis_key_term where analysis_id = ? and label = '휴일'", analysisId);
        assertThat(row.get("value")).isNull();
        assertThat(row.get("source")).isNull();
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

    long newAnalysis(long versionId, String status, String step, String failReason, String completedSql) {
        long id = jdbc.queryForObject("insert into analysis (version_id, document_type_code, status, progress_step,"
                + " fail_reason, prompt_version, started_at, completed_at)"
                + " values (?, ?, ?, ?, ?, 'test', now(), " + (completedSql == null ? "null" : completedSql) + ")"
                + " returning analysis_id", Long.class, versionId, typeCode, status, step, failReason);
        analyses.add(id);
        return id;
    }

    void newChunk(long analysisId) {
        jdbc.update("insert into analysis_chunk (analysis_id, chunk_no, page_from, page_to, status, retry_count)"
                + " values (?, 1, 1, 2, 'DONE', 0)", analysisId);
    }

    long newClause(long analysisId, String key, String verdict) {
        return jdbc.queryForObject("insert into analysis_clause (analysis_id, clause_key, article_no, title,"
                + " original_text, page_no, issue_found, verdict, basis_type, priority)"
                + " values (?, ?, '제1조', '조항', '원문', 1, ?, ?, ?, ?) returning clause_id", Long.class,
                analysisId, key, !"OK".equals(verdict), verdict,
                "OK".equals(verdict) ? null : "RULE", "OK".equals(verdict) ? null : 1);
    }
}
