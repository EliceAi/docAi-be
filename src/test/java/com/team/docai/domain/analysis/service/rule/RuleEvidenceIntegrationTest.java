package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.entity.LawHint;
import com.team.docai.domain.analysis.entity.NumberComparison;
import com.team.docai.domain.analysis.entity.RefFoundBy;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5·6단계를 실제 로컬 DB(적재된 법령·꼬리표·rule_param)로 돌린다.
 * 법령·issue_tag·document_type·rule_param 은 운영 데이터라 읽기만 하고, 이 테스트가 만든 사용자·계약서·분석 행만 ID 로 지운다.
 * users, contract, contract_version 은 B 테이블이라 JDBC 로 넣는다 (기존 통합 테스트와 같은 방식).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class RuleEvidenceIntegrationTest {

    @Autowired RuleStepHandler ruleStepHandler;
    @Autowired EvidenceStepHandler evidenceStepHandler;
    @Autowired AnalysisRepository analysisRepository;
    @Autowired AnalysisClauseRepository clauseRepository;
    @Autowired AnalysisKeyTermRepository keyTermRepository;
    @Autowired AnalysisClauseRefRepository refRepository;
    @Autowired RuleWriter ruleWriter;
    @Autowired JdbcTemplate jdbc;

    UUID userId;
    Long contractId;
    Long versionId;
    Long analysisId;
    /** 이 테스트가 따로 만든 다른 분석 (다른 분석의 조항 사례) */
    Long otherAnalysisId;
    AnalysisRunContext ctx;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        userId = UUID.randomUUID();
        jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                userId, String.format("%064x", n));
        contractId = jdbc.queryForObject("insert into contract (user_id, document_type_code, archived,"
                + " current_version_no, created_at, updated_at) values (?, 'EMPLOYMENT', false, 1, now(), now())"
                + " returning contract_id", Long.class, userId);
        versionId = jdbc.queryForObject("insert into contract_version (contract_id, version_no, page_count,"
                + " created_at, updated_at) values (?, 1, 2, now(), now()) returning version_id", Long.class, contractId);
        analysisId = analysisRepository.save(Analysis.create(versionId, "EMPLOYMENT", "test-rule", OffsetDateTime.now()))
                .getAnalysisId();
        jdbc.update("update analysis set law_base_date = ? where analysis_id = ?", LocalDate.of(2026, 11, 20), analysisId);
        ctx = new AnalysisRunContext(analysisId, userId, contractId, versionId, "EMPLOYMENT");
    }

    @AfterEach
    void cleanUp() {
        for (Long id : new Long[] {otherAnalysisId, analysisId}) {
            if (id == null) {
                continue;
            }
            jdbc.update("delete from analysis_clause_ref where clause_id in"
                    + " (select clause_id from analysis_clause where analysis_id = ?)", id);
            jdbc.update("delete from analysis_key_term where analysis_id = ?", id);
            jdbc.update("delete from analysis_finding where analysis_id = ?", id);
            jdbc.update("delete from analysis_clause where analysis_id = ?", id);
            jdbc.update("delete from analysis where analysis_id = ?", id);
        }
        jdbc.update("delete from contract_version where version_id = ?", versionId);
        jdbc.update("delete from contract where contract_id = ?", contractId);
        jdbc.update("delete from users where user_id = ?", userId);
    }

    AnalysisClause clause(String key, String articleNo, boolean issue, String tag, List<LawHint> hints,
                          List<String> keywords) {
        return clause(analysisId, key, articleNo, issue, tag, hints, keywords);
    }

    AnalysisClause clause(Long ownerAnalysisId, String key, String articleNo, boolean issue, String tag, List<LawHint> hints,
                          List<String> keywords) {
        return clauseRepository.save(AnalysisClause.create(ownerAnalysisId, key, articleNo, "제목", "원문 " + key, "한 줄",
                1, null, issue, issue ? "요약" : null, tag, hints, keywords));
    }

    void keyTerm(String label, Long clauseId, Map<String, Object> valueJson, KeyTermState state, int order) {
        keyTermRepository.save(AnalysisKeyTerm.create(analysisId, clauseId, label,
                state == KeyTermState.MISSING ? null : "값", valueJson, state,
                state == KeyTermState.MISSING ? null : KeyTermSource.WRITTEN, null, order));
    }

    Long articleId(String law, int no, Integer paragraph) {
        return jdbc.queryForObject("select a.law_article_id from law_article a join law l on l.law_id = a.law_id"
                + " where l.name = ? and a.article_no = ? and a.article_branch_no = 0"
                + " and coalesce(a.paragraph_no, 0) = ? and a.effective_to is null", Long.class,
                law, no, paragraph == null ? 0 : paragraph);
    }

    void run() {
        ruleStepHandler.execute(ctx);
        evidenceStepHandler.execute(ctx);
    }

    @Test
    void 위약금_꼬리표에_근로기준법_제20조가_붙고_최저임금_미달과_빠진_휴일을_저장한다() {
        AnalysisClause wageClause = clause("c4", "제4조", false, null, null, null);
        AnalysisClause penalty = clause("c7", "제7조", true, "PENALTY_ON_RESIGNATION",
                List.of(new LawHint("근로기준법", "제20조"), new LawHint("없는법", "제1조")), List.of("위약금", "교육비"));
        AnalysisClause normal = clause("c8", "제8조", false, null, null, null);
        Map<String, Object> wage = new HashMap<>(Map.of("type", "HOURLY", "amount", 10000));
        keyTerm("임금", wageClause.getClauseId(), wage, KeyTermState.OK, 1);
        keyTerm("근로시간", wageClause.getClauseId(), Map.of("hoursPerDay", 4, "daysPerWeek", 5), KeyTermState.OK, 2);
        keyTerm("휴일", null, null, KeyTermState.MISSING, 3);
        keyTerm("연차휴가", null, null, KeyTermState.MASKED, 4);
        keyTerm("근무장소", null, null, KeyTermState.OK, 5);
        keyTerm("업무", null, null, KeyTermState.OK, 6);

        run();

        Long art20 = articleId("근로기준법", 20, null);
        List<AnalysisClauseRef> penaltyRefs = refRepository.findByClauseIdOrderByRefIdAsc(penalty.getClauseId());
        assertThat(penaltyRefs).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        AnalysisClauseRef first = penaltyRefs.get(0);
        assertThat(first.getRefLabel()).isEqualTo("L1");
        assertThat(first.getLawArticleId()).isEqualTo(art20);
        assertThat(first.getFoundBy()).isEqualTo(RefFoundBy.TAG_MAP);
        assertThat(first.isCited()).isFalse();
        assertThat(first.getRefTitle()).isEqualTo("근로기준법 제20조 (위약 예정의 금지)");
        assertThat(first.getRefText()).contains("위약금");
        assertThat(penaltyRefs).extracting(AnalysisClauseRef::getLawArticleId).doesNotHaveDuplicates();
        assertThat(penaltyRefs).extracting(AnalysisClauseRef::getRefLabel).doesNotHaveDuplicates();
        assertThat(penaltyRefs).noneMatch(AnalysisClauseRef::isCited);

        // 최저임금: 시급 10,000원 < 10,320원 → 임금 조항에 의심 표시와 RULE 근거
        Long ruleArticle = jdbc.queryForObject("select law_article_id from rule_param where rule_code = 'MIN_WAGE'"
                + " and valid_from <= ? order by valid_from desc limit 1", Long.class, LocalDate.of(2026, 11, 20));
        Map<String, Object> row = jdbc.queryForMap("select issue_found, basis_type, verdict, headline, why_check,"
                + " issue_tag_code, comparison_json->>'leftValue' as lv, comparison_json->>'rightValue' as rv,"
                + " comparison_json->>'operator' as op from analysis_clause where clause_id = ?", wageClause.getClauseId());
        assertThat(row.get("issue_found")).isEqualTo(true);
        assertThat(row.get("basis_type")).isEqualTo("RULE");
        assertThat(row.get("verdict")).isEqualTo("JUDGING");
        assertThat(row.get("headline")).isEqualTo("시급이 2026년 최저임금보다 낮아요");
        assertThat((String) row.get("why_check")).contains("시간당 320원");
        assertThat(row.get("issue_tag_code")).isEqualTo("MIN_WAGE_SHORTFALL");
        assertThat(row.get("lv")).isEqualTo("10,000원");
        assertThat(row.get("rv")).isEqualTo("10,320원");
        assertThat(row.get("op")).isEqualTo("<");
        List<AnalysisClauseRef> wageRefs = refRepository.findByClauseIdOrderByRefIdAsc(wageClause.getClauseId());
        assertThat(wageRefs.get(0).getFoundBy()).isEqualTo(RefFoundBy.RULE);
        assertThat(wageRefs.get(0).isCited()).isTrue();
        assertThat(wageRefs.get(0).getRefLabel()).isEqualTo("L1");
        assertThat(wageRefs.get(0).getLawArticleId()).isEqualTo(ruleArticle);
        assertThat(wageRefs).hasSizeLessThanOrEqualTo(5);
        assertThat(wageRefs).extracting(AnalysisClauseRef::getLawArticleId).doesNotHaveDuplicates();
        assertThat(wageRefs.subList(1, wageRefs.size())).allMatch(r -> !r.isCited()
                && r.getFoundBy() == RefFoundBy.TAG_MAP);

        // 의심 표시 없는 조항은 근거를 찾지 않는다
        assertThat(refRepository.findByClauseIdOrderByRefIdAsc(normal.getClauseId())).isEmpty();

        // 빠진 항목: 휴일만 (연차휴가는 가려서 읽지 않음 → 누락 아님)
        List<Map<String, Object>> findings = jdbc.queryForList("select kind, name, description, due_date,"
                + " law_article_id, sort_order from analysis_finding where analysis_id = ?", analysisId);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).get("kind")).isEqualTo("MISSING_ITEM");
        assertThat(findings.get(0).get("name")).isEqualTo("휴일");
        assertThat(findings.get(0).get("due_date")).isNull();
        assertThat(findings.get(0).get("law_article_id")).isEqualTo(articleId("근로기준법", 17, 1));
    }

    @Test
    void 정답세트_01은_최저임금_통과_빠진_항목_0_근거_0() {
        String[] labels = {"계약기간", "근무장소", "업무", "근로시간", "휴일", "임금", "임금 지급일", "연차휴가"};
        AnalysisClause wageClause = clause("c6", "제6조", false, null, null, null);
        clause("c1", "제1조", false, null, null, null);
        int order = 1;
        for (String label : labels) {
            Map<String, Object> json = switch (label) {
                case "계약기간" -> new HashMap<>(Map.of("start", "2026-12-01"));
                case "근로시간" -> Map.of("hoursPerDay", 8, "daysPerWeek", 5);
                case "임금" -> Map.of("type", "MONTHLY", "amount", 2500000);
                default -> null;
            };
            keyTerm(label, label.equals("임금") ? wageClause.getClauseId() : null, json, KeyTermState.OK, order++);
        }

        run();

        Map<String, Object> row = jdbc.queryForMap("select issue_found, basis_type, comparison_json::text as cj"
                + " from analysis_clause where clause_id = ?", wageClause.getClauseId());
        assertThat(row.get("issue_found")).isEqualTo(false);
        assertThat(row.get("basis_type")).isNull();
        assertThat(row.get("cj")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from analysis_finding where analysis_id = ?", Long.class,
                analysisId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analysis_clause_ref r join analysis_clause c"
                + " on c.clause_id = r.clause_id where c.analysis_id = ?", Long.class, analysisId)).isZero();
    }

    @Test
    void 정답세트_04_네_시간_휴게_없음은_근로시간_조항에_휴게_규칙_결과와_제54조_근거가_붙는다() {
        AnalysisClause hoursClause = clause("c4", "제4조", false, null, null, null);
        AnalysisClause wageClause = clause("c6", "제6조", false, null, null, null);
        Map<String, Object> hours = new HashMap<>();
        hours.put("hoursPerDay", 4);
        hours.put("daysPerWeek", 5);
        hours.put("workStart", "14:00");
        hours.put("workEnd", "18:00");
        hours.put("noBreak", true);
        keyTerm("근로시간", hoursClause.getClauseId(), hours, KeyTermState.OK, 1);
        keyTerm("임금", wageClause.getClauseId(), Map.of("type", "HOURLY", "amount", 10320), KeyTermState.OK, 2);

        run();

        Map<String, Object> row = jdbc.queryForMap("select issue_found, basis_type, verdict, headline, why_check,"
                + " issue_tag_code, comparison_json->>'leftValue' as lv, comparison_json->>'rightValue' as rv"
                + " from analysis_clause where clause_id = ?", hoursClause.getClauseId());
        assertThat(row.get("issue_found")).isEqualTo(true);
        assertThat(row.get("basis_type")).isEqualTo("RULE");
        assertThat(row.get("verdict")).isEqualTo("JUDGING");
        assertThat(row.get("headline")).isEqualTo("하루 4시간 일하는데 휴게시간이 없어요");
        assertThat((String) row.get("why_check")).contains("4시간이면 30분");
        assertThat(row.get("issue_tag_code")).isEqualTo("NO_BREAK_TIME");
        assertThat(row.get("lv")).isEqualTo("0분");
        assertThat(row.get("rv")).isEqualTo("30분");
        List<AnalysisClauseRef> refs = refRepository.findByClauseIdOrderByRefIdAsc(hoursClause.getClauseId());
        assertThat(refs).extracting(AnalysisClauseRef::getLawArticleId).doesNotHaveDuplicates();
        // 규칙 근거는 제54조 제1항 하나 (RULE, 인용). 제2항은 6단계 꼬리표 조문으로 붙고 인용하지 않는다
        AnalysisClauseRef ruleRef = refs.get(0);
        assertThat(ruleRef.getLawArticleId()).isEqualTo(articleId("근로기준법", 54, 1));
        assertThat(ruleRef.getRefLabel()).isEqualTo("L1");
        assertThat(ruleRef.getFoundBy()).isEqualTo(RefFoundBy.RULE);
        assertThat(ruleRef.isCited()).isTrue();
        assertThat(refs.subList(1, refs.size())).allMatch(r -> !r.isCited() && r.getFoundBy() != RefFoundBy.RULE);
        assertThat(refs).filteredOn(r -> r.getLawArticleId().equals(articleId("근로기준법", 54, 2)))
                .singleElement().satisfies(r -> {
                    assertThat(r.getFoundBy()).isEqualTo(RefFoundBy.TAG_MAP);
                    assertThat(r.isCited()).isFalse();
                });
        // 최저임금은 통과 (10,320원 = 2026년 최저임금)
        assertThat(clauseRow(wageClause.getClauseId()).get("basis_type")).isNull();

        // 다시 돌려도 근거·이유가 겹쳐 붙지 않는다
        ruleStepHandler.checkBreakTime(analysisId, keyTermRepository.findByAnalysisIdOrderBySortOrderAsc(analysisId),
                LocalDate.of(2026, 11, 20));
        assertThat(refRepository.findByClauseIdOrderByRefIdAsc(hoursClause.getClauseId())).hasSameSizeAs(refs);
        assertThat(clauseRow(hoursClause.getClauseId()).get("why_check")).isEqualTo(row.get("why_check"));
    }

    @Test
    void 정답세트_03_휴게_1시간과_정보_없는_근로시간은_휴게_규칙을_붙이지_않는다() {
        AnalysisClause hoursClause = clause("c4", "제4조", false, null, null, null);
        Map<String, Object> hours = new HashMap<>();
        hours.put("hoursPerDay", 8);
        hours.put("daysPerWeek", 5);
        hours.put("workStart", "09:00");
        hours.put("workEnd", "18:00");
        hours.put("breaks", List.of(Map.of("start", "12:00", "end", "13:00")));
        keyTerm("근로시간", hoursClause.getClauseId(), hours, KeyTermState.OK, 1);

        ruleStepHandler.checkBreakTime(analysisId, keyTermRepository.findByAnalysisIdOrderBySortOrderAsc(analysisId),
                LocalDate.of(2026, 11, 20));

        assertThat(clauseRow(hoursClause.getClauseId()).get("basis_type")).isNull();
        assertThat(refRepository.findByClauseIdOrderByRefIdAsc(hoursClause.getClauseId())).isEmpty();
    }

    @Test
    void 최저임금과_휴게가_같은_조항이면_앞_규칙의_제목은_두고_이유와_근거를_더한다() {
        AnalysisClause c = clause("c4", "제4조", false, null, null, null);
        Map<String, Object> hours = new HashMap<>();
        hours.put("hoursPerDay", 9);
        hours.put("daysPerWeek", 5);
        hours.put("workStart", "09:00");
        hours.put("workEnd", "18:00");
        hours.put("noBreak", true);
        keyTerm("근로시간", c.getClauseId(), hours, KeyTermState.OK, 1);
        keyTerm("임금", c.getClauseId(), Map.of("type", "HOURLY", "amount", 10000), KeyTermState.OK, 2);

        ruleStepHandler.execute(ctx);

        Map<String, Object> row = clauseRow(c.getClauseId());
        assertThat(row.get("basis_type")).isEqualTo("RULE");
        assertThat(row.get("headline")).isEqualTo("시급이 2026년 최저임금보다 낮아요");
        assertThat(row.get("issue_tag_code")).isEqualTo("MIN_WAGE_SHORTFALL");
        assertThat((String) row.get("why_check")).contains("시간당 320원").contains("8시간이면 1시간");
        List<AnalysisClauseRef> refs = refRepository.findByClauseIdOrderByRefIdAsc(c.getClauseId());
        assertThat(refs).extracting(AnalysisClauseRef::getRefLabel).containsExactly("L1", "L2");
        assertThat(refs).allMatch(r -> r.getFoundBy() == RefFoundBy.RULE && r.isCited());
        assertThat(refs.get(1).getLawArticleId()).isEqualTo(articleId("근로기준법", 54, 1));

        // 다시 돌려도 최저임금·휴게 근거와 이유가 겹쳐 붙지 않는다
        String why = (String) row.get("why_check");
        ruleStepHandler.execute(ctx);
        assertThat(refRepository.findByClauseIdOrderByRefIdAsc(c.getClauseId())).hasSize(2);
        assertThat(clauseRow(c.getClauseId()).get("why_check")).isEqualTo(why);
    }

    EvidenceCollector.RefDraft ruleRef(Long clauseId) {
        return new EvidenceCollector.RefDraft(clauseId, "L1", articleId("최저임금법", 6, 1), null,
                RefFoundBy.RULE, true, "최저임금법 제6조제1항", "내용");
    }

    boolean apply(Long ownerAnalysisId, Long clauseId) {
        return ruleWriter.applyRuleResult(ownerAnalysisId, clauseId,
                new NumberComparison("계약서 시급", "10,000원", "<", "2026년 최저임금", "10,320원"),
                "시급이 2026년 최저임금보다 낮아요", "이유", ruleRef(clauseId));
    }

    Map<String, Object> clauseRow(Long clauseId) {
        return jdbc.queryForMap("select issue_found, basis_type, headline, why_check, issue_tag_code,"
                + " comparison_json::text as cj, verdict, excluded from analysis_clause where clause_id = ?", clauseId);
    }

    @Test
    void 판정_중이_아니거나_제외됐거나_다른_분석의_조항이면_규칙_결과를_붙이지_않는다() {
        AnalysisClause judged = clause("c1", "제1조", false, null, null, null);
        jdbc.update("update analysis_clause set verdict = 'NEEDS_CHECK' where clause_id = ?", judged.getClauseId());
        AnalysisClause excluded = clause("c2", "제2조", false, null, null, null);
        jdbc.update("update analysis_clause set excluded = true where clause_id = ?", excluded.getClauseId());
        otherAnalysisId = jdbc.queryForObject("insert into analysis (version_id, document_type_code, status,"
                + " progress_step, fail_reason, prompt_version, started_at, completed_at) values (?, 'EMPLOYMENT',"
                + " 'FAILED', 'FINDING_TERMS', 'TEMPORARY_ERROR', 'test-rule', now(), now()) returning analysis_id",
                Long.class, versionId);
        AnalysisClause foreign = clause(otherAnalysisId, "c1", "제1조", false, null, null, null);

        for (AnalysisClause c : List.of(judged, excluded, foreign)) {
            Map<String, Object> before = clauseRow(c.getClauseId());
            assertThat(apply(analysisId, c.getClauseId())).isFalse();
            assertThat(clauseRow(c.getClauseId())).isEqualTo(before);
            assertThat(refRepository.findByClauseIdOrderByRefIdAsc(c.getClauseId())).isEmpty();
        }
        // 대조: 판정 중인 이 분석의 조항에는 붙는다
        AnalysisClause judging = clause("c3", "제3조", false, null, null, null);
        assertThat(apply(analysisId, judging.getClauseId())).isTrue();
        assertThat(clauseRow(judging.getClauseId()).get("basis_type")).isEqualTo("RULE");
        assertThat(refRepository.findByClauseIdOrderByRefIdAsc(judging.getClauseId())).hasSize(1);
    }

    @Test
    void 꼬리표가_없으면_AI_힌트로_찾은_조문을_AI_HINT로_저장한다() {
        AnalysisClause c = clause("c7", "제7조", true, null, List.of(new LawHint("근로기준법", "제 20조")), null);

        evidenceStepHandler.execute(ctx);

        List<AnalysisClauseRef> refs = refRepository.findByClauseIdOrderByRefIdAsc(c.getClauseId());
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getFoundBy()).isEqualTo(RefFoundBy.AI_HINT);
        assertThat(refs.get(0).getLawArticleId()).isEqualTo(articleId("근로기준법", 20, null));
        assertThat(refs.get(0).getRefLabel()).isEqualTo("L1");
        assertThat(refs.get(0).isCited()).isFalse();
    }
}
