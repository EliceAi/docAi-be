package com.team.docai.domain.analysis.service.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.service.judge.JudgeWriter.ClauseJudgment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JudgeWriter 를 실제 DB(V2 스키마)로: 조건부 UPDATE(판정 중·제외 아님·그 분석의 조항만), 끝난 분석이면 저장하지 않음,
 * 규칙 결과 조항의 headline·why_check·basis_type 보존, 인용·관련 조항·OK 채우기·질문·검증 기록.
 * users, contract, contract_version 은 B 테이블이라 JDBC 로 넣고, 이 테스트가 만든 행만 ID 로 지운다.
 * 근거 행의 law_article_id 는 운영 조문 하나를 읽어서 쓴다 (법령 행은 읽기만).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class JudgeWriterIntegrationTest {

    @Autowired JudgeWriter judgeWriter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    UUID userId;
    Long contractId;
    Long versionId;
    Long analysisId;
    Long otherAnalysisId;
    Long articleId;
    long rule, ai, okAlready, excluded, plain, foreign;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                userId, String.format("%064x", System.nanoTime()));
        contractId = jdbc.queryForObject("insert into contract (user_id, document_type_code, archived,"
                + " current_version_no, created_at, updated_at) values (?, 'EMPLOYMENT', false, 1, now(), now())"
                + " returning contract_id", Long.class, userId);
        versionId = jdbc.queryForObject("insert into contract_version (contract_id, version_no, page_count,"
                + " created_at, updated_at) values (?, 1, 1, now(), now()) returning version_id", Long.class, contractId);
        analysisId = newAnalysis("ANALYZING", null);
        otherAnalysisId = newAnalysis("FAILED", "TEMPORARY_ERROR");
        articleId = jdbc.queryForObject("select min(law_article_id) from law_article", Long.class);

        rule = clause(analysisId, "c1", true, "JUDGING", false);
        jdbc.update("update analysis_clause set basis_type = 'RULE', headline = '규칙 제목', why_check = '규칙 이유'"
                + " where clause_id = ?", rule);
        ai = clause(analysisId, "c2", true, "JUDGING", false);
        okAlready = clause(analysisId, "c3", true, "NEEDS_CHECK", false);
        excluded = clause(analysisId, "c4", true, "JUDGING", true);
        plain = clause(analysisId, "c5", false, "JUDGING", false);
        foreign = clause(otherAnalysisId, "c1", true, "JUDGING", false);
        ref(ai, "L1", false);
        ref(ai, "L2", false);
        ref(rule, "L1", true);
        jdbc.update("update analysis set validation_log_json = '{\"extract\":[{\"type\":\"X\"}]}'::jsonb"
                + " where analysis_id = ?", analysisId);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : new Long[] {analysisId, otherAnalysisId}) {
            jdbc.update("delete from analysis_clause_ref where clause_id in"
                    + " (select clause_id from analysis_clause where analysis_id = ?)", id);
            jdbc.update("delete from analysis_clause_relation where analysis_id = ?", id);
            jdbc.update("delete from analysis_clause where analysis_id = ?", id);
            jdbc.update("delete from analysis where analysis_id = ?", id);
        }
        jdbc.update("delete from contract_version where version_id = ?", versionId);
        jdbc.update("delete from contract where contract_id = ?", contractId);
        jdbc.update("delete from users where user_id = ?", userId);
    }

    Long newAnalysis(String status, String failReason) {
        return jdbc.queryForObject("insert into analysis (version_id, document_type_code, status, progress_step,"
                + " fail_reason, prompt_version, started_at, completed_at, heartbeat_at) values (?, 'EMPLOYMENT', ?,"
                + " 'MATCHING_LAWS', ?, 'test-judge', now(), " + ("ANALYZING".equals(status) ? "null" : "now()")
                + ", now()) returning analysis_id", Long.class, versionId, status, failReason);
    }

    long clause(Long owner, String key, boolean issue, String verdict, boolean isExcluded) {
        return jdbc.queryForObject("insert into analysis_clause (analysis_id, clause_key, original_text, page_no,"
                + " issue_found, verdict, excluded) values (?, ?, '원문', 1, ?, ?, ?) returning clause_id",
                Long.class, owner, key, issue, verdict, isExcluded);
    }

    void ref(long clauseId, String label, boolean cited) {
        jdbc.update("insert into analysis_clause_ref (clause_id, ref_label, law_article_id, found_by, cited, ref_title,"
                + " ref_text) values (?, ?, ?, ?, ?, '제목', '내용')", clauseId, label, articleId,
                cited ? "RULE" : "TAG_MAP", cited);
    }

    Map<String, Object> row(long clauseId) {
        return jdbc.queryForMap("select verdict, basis_type, headline, why_check, easy_explanation, priority,"
                + " ask_counterpart_json::text as ask from analysis_clause where clause_id = ?", clauseId);
    }

    ClauseJudgment judgment(long clauseId, ClauseVerdict verdict, List<String> cited, List<Long> related, int priority) {
        return new ClauseJudgment(clauseId, verdict, "AI 제목", "AI 쉬운 설명", "AI 이유", cited, related,
                List.of("물어볼 것?"), priority);
    }

    @Test
    void 판정_중인_이_분석의_조항만_바꾸고_규칙_결과_문구는_지키며_의심_없는_조항은_OK() throws Exception {
        Map<String, Object> okBefore = row(okAlready);
        Map<String, Object> excludedBefore = row(excluded);
        Map<String, Object> foreignBefore = row(foreign);

        boolean saved = judgeWriter.saveJudgments(analysisId, List.of(
                judgment(rule, ClauseVerdict.NEEDS_CHECK, List.of("L1"), List.of(), 1),
                judgment(ai, ClauseVerdict.NEEDS_CHECK, List.of("L2"), List.of(rule, rule), 2),
                judgment(okAlready, ClauseVerdict.UNCERTAIN, List.of(), List.of(), 3),
                judgment(excluded, ClauseVerdict.NEEDS_CHECK, List.of(), List.of(), 4),
                judgment(foreign, ClauseVerdict.NEEDS_CHECK, List.of(), List.of(), 5)),
                List.of("서명 전 질문?"), List.of(Map.of("stage", "JUDGE", "type", "CITATION_REMOVED")));

        assertThat(saved).isTrue();
        Map<String, Object> r = row(rule);
        assertThat(r.get("verdict")).isEqualTo("NEEDS_CHECK");
        assertThat(r.get("basis_type")).isEqualTo("RULE");
        assertThat(r.get("headline")).isEqualTo("규칙 제목");
        assertThat(r.get("why_check")).isEqualTo("규칙 이유");
        assertThat(r.get("easy_explanation")).isEqualTo("AI 쉬운 설명");
        Map<String, Object> a = row(ai);
        assertThat(a.get("verdict")).isEqualTo("NEEDS_CHECK");
        assertThat(a.get("basis_type")).isEqualTo("AI_INTERPRETATION");
        assertThat(a.get("headline")).isEqualTo("AI 제목");
        assertThat(a.get("why_check")).isEqualTo("AI 이유");
        assertThat(a.get("priority")).isEqualTo(2);
        assertThat(mapper.readTree((String) a.get("ask")).get(0).asText()).isEqualTo("물어볼 것?");
        assertThat(jdbc.queryForList("select ref_label from analysis_clause_ref where clause_id = ? and cited = true",
                String.class, ai)).containsExactly("L2");
        assertThat(jdbc.queryForList("select related_clause_id from analysis_clause_relation where clause_id = ?",
                Long.class, ai)).containsExactly(rule);
        // 판정 중이 아닌 조항, 제외된 조항, 다른 분석의 조항은 그대로
        assertThat(row(okAlready)).isEqualTo(okBefore);
        assertThat(row(excluded)).isEqualTo(excludedBefore);
        assertThat(row(foreign)).isEqualTo(foreignBefore);
        // 의심 표시 없는 판정 중 조항 → OK
        assertThat(row(plain).get("verdict")).isEqualTo("OK");
        Map<String, Object> analysis = jdbc.queryForMap("select progress_step, questions_json::text as q,"
                + " validation_log_json::text as vl from analysis where analysis_id = ?", analysisId);
        assertThat(analysis.get("progress_step")).isEqualTo("SUMMARIZING");
        assertThat(mapper.readTree((String) analysis.get("q")).get(0).asText()).isEqualTo("서명 전 질문?");
        assertThat(mapper.readTree((String) analysis.get("vl")).get("extract")).hasSize(1);
        assertThat(mapper.readTree((String) analysis.get("vl")).get("judge").get(0).get("type").asText())
                .isEqualTo("CITATION_REMOVED");
    }

    @Test
    void 이미_끝난_분석이면_아무것도_저장하지_않는다() {
        jdbc.update("update analysis set status = 'FAILED', fail_reason = 'TEMPORARY_ERROR', completed_at = now()"
                + " where analysis_id = ?", analysisId);
        Map<String, Object> aiBefore = row(ai);

        boolean saved = judgeWriter.saveJudgments(analysisId,
                List.of(judgment(ai, ClauseVerdict.NEEDS_CHECK, List.of("L1"), List.of(rule), 1)),
                List.of("질문?"), List.of());

        assertThat(saved).isFalse();
        assertThat(row(ai)).isEqualTo(aiBefore);
        assertThat(row(plain).get("verdict")).isEqualTo("JUDGING");
        assertThat(jdbc.queryForObject("select count(*) from analysis_clause_ref where clause_id = ? and cited = true",
                Long.class, ai)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analysis_clause_relation where analysis_id = ?",
                Long.class, analysisId)).isZero();
        assertThat(jdbc.queryForObject("select questions_json is null from analysis where analysis_id = ?",
                Boolean.class, analysisId)).isTrue();
    }
}
