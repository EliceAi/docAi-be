package com.team.docai.domain.analysis.service.query;

import com.team.docai.global.auth.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 결과·조항 상세·예시 분석 조회를 실제 DB(V2 스키마)로 확인한다.
 * <ul>
 *   <li>계약서·버전·쪽, 분석·묶음·조항·근거·관련 조항·주요 조건·규칙 결과·일정 후보, 법령·조문, 꼬리표·해석 메모, 일정을
 *       이 테스트가 직접 INSERT 하고 끝나면 그 ID 로만 지운다. 문서 종류도 이 테스트 전용 코드로 만든다.</li>
 *   <li>판정 중(JUDGING)·제외(excluded) 조항, 인용 안 된 근거·해석 메모, UNCERTAIN 조항의 법적 판단 문구·법령이 어디에도 나오지 않는지,
 *       남의 것·삭제된 계약서·다른 분석의 조항이 404 인지 본다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class AnalysisQueryIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokenService;

    final String suffix = Long.toString(System.nanoTime(), 36).toUpperCase();
    final String typeCode = "TQ_" + suffix;
    final String tagCode = "TQ_TAG_" + suffix;

    final List<UUID> users = new ArrayList<>();
    final List<Long> contracts = new ArrayList<>();
    final List<Long> versions = new ArrayList<>();
    final List<Long> analyses = new ArrayList<>();
    final List<Long> laws = new ArrayList<>();
    Long guidanceId;

    UUID owner;
    UUID other;
    long c1;
    long c2;
    long v1;
    long done;
    long running;
    long failed;
    long selfSimilar;
    long foreignSimilar;
    long othersAnalysis;
    long deletedAnalysis;
    long othersClause;
    long k1, k2, k3, k4, k5, k6, k7;
    long art61, art62, art17, art20;

    @BeforeEach
    void setUp() {
        owner = newUser();
        other = newUser();
        jdbc.update("insert into document_type (code, name, category, situation, description, check_level, supported,"
                + " popular, sort_order) values (?, '테스트 계약서', 'WORK', '테스트', '테스트', 'A', true, false, 999)", typeCode);
        jdbc.update("insert into issue_tag (code, document_type_code, name) values (?, ?, '테스트 꼬리표')", tagCode, typeCode);
        guidanceId = jdbc.queryForObject("insert into guidance (issue_tag_code, title, content, source_note, reviewed_at)"
                + " values (?, '해석 메모', '메모 내용', '출처', current_date) returning guidance_id", Long.class, tagCode);

        long minWageLaw = newLaw("테스트최저임금법" + suffix);
        long laborLaw = newLaw("테스트근로기준법" + suffix);
        art61 = newArticle(minWageLaw, 6, 1, "최저임금의 효력", "① 사용자는 최저임금 이상을 지급하여야 한다.");
        art62 = newArticle(minWageLaw, 6, 2, "최저임금의 효력", "② 사용자는 최저임금을 이유로 임금을 낮추어서는 아니 된다.");
        art17 = newArticle(laborLaw, 17, 1, "근로조건의 명시", "① 사용자는 근로계약을 체결할 때에 ...");
        art20 = newArticle(laborLaw, 20, null, "위약 예정의 금지", "사용자는 근로계약 불이행에 대한 위약금을 예정하지 못한다.");

        // 내 계약서 C1 (2쪽, 가린 곳 2 + 3)
        c1 = newContract(owner, "카페 알바 근로계약서", false, false);
        v1 = newVersion(c1, 1, 2);
        newPage(v1, 1, 2);
        newPage(v1, 2, 3);

        // 비슷한 계약서 C2 (보관됨, 최신 분석 있음, 일정 1개)
        c2 = newContract(owner, "이전 알바", true, false);
        long v2 = newVersion(c2, 1, 1);
        long simAnalysis = newAnalysis(v2, "COMPLETED", "DONE", null, null, "'2026-10-01 00:00:00+00'", null);
        jdbc.update("update contract_version set latest_analysis_id = ? where version_id = ?", simAnalysis, v2);
        jdbc.update("insert into schedule (user_id, contract_id, title, date, source, created_at, updated_at)"
                + " values (?, ?, '계약 시작일', date '2026-12-01', 'WRITTEN', now(), now())", owner, c2);

        // 완료된 분석
        done = newAnalysis(v1, "COMPLETED", "DONE", null, null, "'2026-11-12 05:20:00+00'", c2);
        jdbc.update("update analysis set partial_pages = '[2]'::jsonb, questions_json = '[\"시급을 고쳐 주실 수 있나요?\"]'::jsonb,"
                + " limitations_json = '[\"등기부는 따로 확인\"]'::jsonb where analysis_id = ?", done);
        newChunk(done, 1, 1, 2, "DONE");

        k1 = newClause(done, "c1", "제1조", "계약 기간", "계약기간: 2026.12.01 ~ 2027.02.28", 1, "OK", null, null, false);
        jdbc.update("update analysis_clause set issue_summary = '남은 요약', why_check = '남은 이유' where clause_id = ?", k1);
        k2 = newClause(done, "c2", "제4조", "임금", "시급 10,000원", 1, "NEEDS_CHECK", "RULE", 1, false);
        jdbc.update("update analysis_clause set headline = '시급이 최저임금보다 낮아요', why_check = '시간당 320원 적어요.',"
                + " issue_summary = '최저임금 미달', easy_explanation = '시급이 법보다 낮아요.',"
                + " comparison_json = '{\"leftLabel\":\"계약서 시급\",\"leftValue\":\"10,000원\",\"operator\":\"<\","
                + "\"rightLabel\":\"2026년 최저임금\",\"rightValue\":\"10,320원\"}'::jsonb,"
                + " ask_counterpart_json = '[\"시급을 올려 주실 수 있나요?\"]'::jsonb, location = '1쪽 제4조' where clause_id = ?", k2);
        k3 = newClause(done, "c3", "제7조", "교육비 반환", "1년 이내 퇴사 시 교육비 30만 원 반환", 2, "UNCERTAIN",
                "AI_INTERPRETATION", 2, false);
        jdbc.update("update analysis_clause set headline = '교육비 반환', why_check = '법으로 금지된 위약금이에요.',"
                + " issue_summary = '위약금 예정 금지 위반', easy_explanation = '일찍 그만두면 교육비를 물어내야 해요.',"
                + " ask_counterpart_json = '[\"교육비 반환 조건을 빼 주실 수 있나요?\"]'::jsonb where clause_id = ?", k3);
        k4 = newClause(done, "c4", "제8조", "지어낸 조항", "원문에 없음", 2, "NEEDS_CHECK", "AI_INTERPRETATION", 3, true);
        k5 = newClause(done, "c5", "제9조", null, null, 2, "UNREADABLE", null, null, false);
        k6 = newClause(done, "c6", "제10조", "판정 중", "판정 중 원문", 2, "JUDGING", null, null, false);
        k7 = newClause(done, "c7", "제11조", "위약금", "위반 시 100만 원", 2, "NEEDS_CHECK", "AI_INTERPRETATION", null, false);
        jdbc.update("update analysis_clause set why_check = '위약금 예정은 금지돼 있어요.' where clause_id = ?", k7);

        newRef(k2, "R1", art61, null, "RULE", true, "최저임금법 제6조제1항 스냅샷");
        newRef(k2, "R2", art62, null, "RULE", true, "최저임금법 제6조제2항 스냅샷");
        newRef(k3, "L1", art20, null, "TAG_MAP", true, "UNCERTAIN 이라 숨길 근거");
        newRef(k7, "L1", art20, null, "AI_HINT", true, "근로기준법 제20조 당시 내용");
        newRef(k7, "L2", art17, null, "SEARCH", false, "인용 안 된 근거");
        newRef(k7, "G1", null, guidanceId, "TAG_MAP", true, "해석 메모는 보여 주지 않음");

        newRelation(done, k3, k1);
        newRelation(done, k3, k4);
        newRelation(done, k3, k6);
        newRelation(done, k2, k5);

        jdbc.update("insert into analysis_key_term (analysis_id, label, value, state, source, sort_order)"
                + " values (?, '임금', '시급 10,000원', 'NEEDS_CHECK', 'WRITTEN', 1), (?, '사업주', null, 'MASKED', null, 2)",
                done, done);
        jdbc.update("insert into analysis_finding (analysis_id, kind, name, description, due_date, law_article_id, sort_order)"
                + " values (?, 'MISSING_ITEM', '휴일', '주휴일을 꼭 적어야 해요', null, ?, 1),"
                + " (?, 'DEADLINE', '신고', '30일 안에 신고', date '2026-12-20', null, 2)", done, art17, done);
        jdbc.update("insert into schedule_candidate (analysis_id, date_kind, title, date, source, default_selected)"
                + " values (?, 'CONTRACT_START', '계약 시작일', date '2026-12-01', 'WRITTEN', true),"
                + " (?, 'PAYDAY', '급여일', null, 'WRITTEN', false)", done, done);

        // 같은 버전의 다른 분석들
        running = newAnalysis(v1, "ANALYZING", "MATCHING_LAWS", null, null, null, null);
        newChunk(running, 1, 1, 1, "DONE");
        newChunk(running, 2, 2, 2, "PENDING");
        failed = newAnalysis(v1, "FAILED", "READING_TEXT", "TYPE_MISMATCH", "임대차계약서", "now()", null);
        selfSimilar = newAnalysis(v1, "COMPLETED", "DONE", null, null, "now()", c1);

        // 남의 계약서 (보관됨) · 삭제된 내 계약서
        long c3 = newContract(other, "남의 계약서", true, false);
        long v3 = newVersion(c3, 1, 1);
        othersAnalysis = newAnalysis(v3, "COMPLETED", "DONE", null, null, "now()", null);
        othersClause = newClause(othersAnalysis, "c1", "제1조", "남의 조항", "남의 원문", 1, "OK", null, null, false);
        foreignSimilar = newAnalysis(v1, "COMPLETED", "DONE", null, null, "now()", c3);

        long c4 = newContract(owner, "지운 계약서", false, true);
        long v4 = newVersion(c4, 1, 1);
        deletedAnalysis = newAnalysis(v4, "COMPLETED", "DONE", null, null, "now()", null);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : contracts) {
            jdbc.update("delete from schedule where contract_id = ?", id);
        }
        for (Long a : analyses) {
            jdbc.update("delete from analysis_clause_ref where clause_id in (select clause_id from analysis_clause where analysis_id = ?)", a);
            jdbc.update("delete from analysis_clause_relation where analysis_id = ?", a);
            jdbc.update("delete from analysis_key_term where analysis_id = ?", a);
            jdbc.update("delete from analysis_finding where analysis_id = ?", a);
            jdbc.update("delete from schedule_candidate where analysis_id = ?", a);
            jdbc.update("delete from analysis_chunk where analysis_id = ?", a);
            jdbc.update("delete from analysis_clause where analysis_id = ?", a);
        }
        for (Long v : versions) {
            jdbc.update("update contract_version set latest_analysis_id = null where version_id = ?", v);
        }
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
        if (guidanceId != null) {
            jdbc.update("delete from guidance where guidance_id = ?", guidanceId);
        }
        jdbc.update("delete from issue_tag where code = ?", tagCode);
        for (Long l : laws) {
            jdbc.update("delete from law_article where law_id = ?", l);
            jdbc.update("delete from law where law_id = ?", l);
        }
        jdbc.update("delete from document_type where code = ?", typeCode);
        for (UUID u : users) {
            jdbc.update("delete from users where user_id = ?", u);
        }
    }

    // ---- 분석 결과 조회 ----

    @Test
    void 완료된_분석은_보이는_조항만으로_결과를_만든다() throws Exception {
        get(owner, "/api/analyses/" + done)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(done))
                .andExpect(jsonPath("$.data.contractId").value(c1))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progressStep").value("DONE"))
                .andExpect(jsonPath("$.data.readProgress.done").value(1))
                .andExpect(jsonPath("$.data.readProgress.total").value(1))
                .andExpect(jsonPath("$.data.failure").value(nullValue()))
                .andExpect(jsonPath("$.data.result.title").value("카페 알바 근로계약서"))
                .andExpect(jsonPath("$.data.result.documentTypeCode").value(typeCode))
                .andExpect(jsonPath("$.data.result.checkLevel").value("A"))
                .andExpect(jsonPath("$.data.result.analyzedAt").value("2026-11-12T14:20:00+09:00"))
                .andExpect(jsonPath("$.data.result.coverage.pageCount").value(2))
                .andExpect(jsonPath("$.data.result.coverage.readPages", contains(1, 2)))
                .andExpect(jsonPath("$.data.result.coverage.partialPages", contains(2)))
                .andExpect(jsonPath("$.data.result.coverage.maskedCount").value(5))
                .andExpect(jsonPath("$.data.result.keyTerms", hasSize(2)))
                .andExpect(jsonPath("$.data.result.keyTerms[0].label").value("임금"))
                .andExpect(jsonPath("$.data.result.keyTerms[0].state").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.data.result.keyTerms[0].source").value("WRITTEN"))
                .andExpect(jsonPath("$.data.result.keyTerms[1].state").value("MASKED"))
                .andExpect(jsonPath("$.data.result.keyTerms[1].value").value(nullValue()))
                .andExpect(jsonPath("$.data.result.keyTerms[1].source").value(nullValue()))
                // 보이는 조항 k1 k2 k3 k5 k7, 확인 필요 k2(NEEDS_CHECK) k3(UNCERTAIN) k7(NEEDS_CHECK)
                .andExpect(jsonPath("$.data.result.counts.needsCheck").value(3))
                .andExpect(jsonPath("$.data.result.counts.missing").value(1))
                .andExpect(jsonPath("$.data.result.counts.deadline").value(1))
                .andExpect(jsonPath("$.data.result.counts.totalClauses").value(5))
                .andExpect(jsonPath("$.data.result.clauses[*].clauseId", contains(n(k1), n(k2), n(k3), n(k5), n(k7))))
                .andExpect(jsonPath("$.data.result.clauses[3].title").value(nullValue()))
                .andExpect(jsonPath("$.data.result.clauses[3].verdict").value("UNREADABLE"))
                .andExpect(jsonPath("$.data.result.clauses[2].pageNo").value(2))
                // 우선 확인: priority 순, 제외된 k4 없음
                .andExpect(jsonPath("$.data.result.priorityClauses[*].clauseId", contains(n(k2), n(k3))))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].verdict").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].title").value("시급이 최저임금보다 낮아요"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].description").value("시간당 320원 적어요."))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].comparison.operator").value("<"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].comparison.rightValue").value("10,320원"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].basisType").value("RULE"))
                // 같은 조의 두 항을 인용해도 "제6조" 하나
                .andExpect(jsonPath("$.data.result.priorityClauses[0].laws", hasSize(1)))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].laws[0].lawName").value("테스트최저임금법" + suffix))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].laws[0].article").value("제6조"))
                // 읽지 못한 조항도 관련 조항으로는 보임 (제목 null)
                .andExpect(jsonPath("$.data.result.priorityClauses[0].relatedClauses[0].clauseId").value(k5))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].relatedClauses[0].title").value(nullValue()))
                // UNCERTAIN: 법적 판단 문구(why_check)와 법령 없음, 관련 조항은 제외·판정 중을 뺀 k1 만
                .andExpect(jsonPath("$.data.result.priorityClauses[1].verdict").value("UNCERTAIN"))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].title").value("교육비 반환"))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].description").value(nullValue()))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].laws", hasSize(0)))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].comparison").value(nullValue()))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].basisType").value("AI_INTERPRETATION"))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].relatedClauses[*].clauseId", contains(n(k1))))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].relatedClauses[0].articleNo").value("제1조"))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].relatedClauses[0].title").value("계약 기간"))
                .andExpect(jsonPath("$.data.result.missingItems[0].name").value("휴일"))
                .andExpect(jsonPath("$.data.result.missingItems[0].description").value("주휴일을 꼭 적어야 해요"))
                .andExpect(jsonPath("$.data.result.missingItems[0].dueDate").value(nullValue()))
                .andExpect(jsonPath("$.data.result.missingItems[0].laws[0].lawName").value("테스트근로기준법" + suffix))
                .andExpect(jsonPath("$.data.result.missingItems[0].laws[0].article").value("제17조"))
                .andExpect(jsonPath("$.data.result.deadlines[0].name").value("신고"))
                .andExpect(jsonPath("$.data.result.deadlines[0].dueDate").value("2026-12-20"))
                .andExpect(jsonPath("$.data.result.deadlines[0].laws", hasSize(0)))
                .andExpect(jsonPath("$.data.result.questionsBeforeSigning", contains("시급을 고쳐 주실 수 있나요?")))
                .andExpect(jsonPath("$.data.result.limitations", contains("등기부는 따로 확인")))
                .andExpect(jsonPath("$.data.result.foundDateCount").value(1))
                .andExpect(jsonPath("$.data.result.similarContract.contractId").value(c2))
                .andExpect(jsonPath("$.data.result.similarContract.title").value("이전 알바"))
                .andExpect(jsonPath("$.data.result.similarContract.analyzedAt").value("2026-10-01T09:00:00+09:00"))
                .andExpect(jsonPath("$.data.result.similarContract.scheduleCount").value(1))
                .andExpect(jsonPath("$.data.result.disclaimer").value(AnalysisQueryService.DISCLAIMER));
    }

    @Test
    void 비슷한_계약서가_자기_자신이거나_남의_것이면_null() throws Exception {
        get(owner, "/api/analyses/" + selfSimilar)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.similarContract").value(nullValue()))
                .andExpect(jsonPath("$.data.result.clauses", hasSize(0)))
                .andExpect(jsonPath("$.data.result.priorityClauses", hasSize(0)))
                .andExpect(jsonPath("$.data.result.questionsBeforeSigning", hasSize(0)))
                .andExpect(jsonPath("$.data.result.counts.totalClauses").value(0));
        get(owner, "/api/analyses/" + foreignSimilar)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.similarContract").value(nullValue()));
    }

    @Test
    void 분석_중이면_진행_단계와_읽기_진행만_준다() throws Exception {
        get(owner, "/api/analyses/" + running)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANALYZING"))
                .andExpect(jsonPath("$.data.progressStep").value("MATCHING_LAWS"))
                .andExpect(jsonPath("$.data.readProgress.done").value(1))
                .andExpect(jsonPath("$.data.readProgress.total").value(2))
                .andExpect(jsonPath("$.data.failure").value(nullValue()))
                .andExpect(jsonPath("$.data.result").value(nullValue()));
    }

    @Test
    void 실패한_분석은_실패_정보만_준다() throws Exception {
        get(owner, "/api/analyses/" + failed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.progressStep").value("READING_TEXT"))
                .andExpect(jsonPath("$.data.failure.reason").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.data.failure.pageNo").value(nullValue()))
                .andExpect(jsonPath("$.data.failure.detectedTypeName").value("임대차계약서"))
                .andExpect(jsonPath("$.data.result").value(nullValue()));
    }

    @Test
    void 결과_조회는_없거나_남의_것이거나_삭제된_계약서의_분석이면_404() throws Exception {
        for (long id : new long[]{othersAnalysis, deletedAnalysis, Long.MAX_VALUE}) {
            get(owner, "/api/analyses/" + id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        }
    }

    // ---- 조항 상세 조회 ----

    @Test
    void 규칙_조항_상세는_인용된_조문을_항_단위로_당시_내용과_함께_준다() throws Exception {
        get(owner, "/api/analyses/" + done + "/clauses/" + k2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clauseId").value(k2))
                .andExpect(jsonPath("$.data.articleNo").value("제4조"))
                .andExpect(jsonPath("$.data.title").value("임금"))
                .andExpect(jsonPath("$.data.verdict").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.data.issueSummary").value("최저임금 미달"))
                .andExpect(jsonPath("$.data.original.text").value("시급 10,000원"))
                .andExpect(jsonPath("$.data.original.pageNo").value(1))
                .andExpect(jsonPath("$.data.original.location").value("1쪽 제4조"))
                .andExpect(jsonPath("$.data.easyExplanation").value("시급이 법보다 낮아요."))
                .andExpect(jsonPath("$.data.whyCheck").value("시간당 320원 적어요."))
                .andExpect(jsonPath("$.data.basisType").value("RULE"))
                .andExpect(jsonPath("$.data.laws", hasSize(2)))
                .andExpect(jsonPath("$.data.laws[0].lawName").value("테스트최저임금법" + suffix))
                .andExpect(jsonPath("$.data.laws[0].article").value("제6조제1항"))
                .andExpect(jsonPath("$.data.laws[0].articleTitle").value("최저임금의 효력"))
                .andExpect(jsonPath("$.data.laws[0].content").value("최저임금법 제6조제1항 스냅샷"))
                .andExpect(jsonPath("$.data.laws[1].article").value("제6조제2항"))
                .andExpect(jsonPath("$.data.relatedClauses[0].clauseId").value(k5))
                .andExpect(jsonPath("$.data.relatedClauses[0].originalText").value(nullValue()))
                .andExpect(jsonPath("$.data.askCounterpart", contains("시급을 올려 주실 수 있나요?")))
                .andExpect(jsonPath("$.data.oneLine").value("제4조 한 줄"));
    }

    @Test
    void 인용_안_된_근거와_해석_메모는_조항_상세에_나오지_않는다() throws Exception {
        get(owner, "/api/analyses/" + done + "/clauses/" + k7)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.laws", hasSize(1)))
                .andExpect(jsonPath("$.data.laws[0].article").value("제20조"))
                .andExpect(jsonPath("$.data.laws[0].articleTitle").value("위약 예정의 금지"))
                .andExpect(jsonPath("$.data.laws[0].content").value("근로기준법 제20조 당시 내용"))
                .andExpect(jsonPath("$.data.basisType").value("AI_INTERPRETATION"))
                .andExpect(jsonPath("$.data.relatedClauses", hasSize(0)))
                .andExpect(jsonPath("$.data.askCounterpart", hasSize(0)));
    }

    @Test
    void UNCERTAIN_조항은_쉬운_설명과_물어볼_것만_주고_법적_판단과_법령은_숨긴다() throws Exception {
        get(owner, "/api/analyses/" + done + "/clauses/" + k3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("UNCERTAIN"))
                .andExpect(jsonPath("$.data.easyExplanation").value("일찍 그만두면 교육비를 물어내야 해요."))
                .andExpect(jsonPath("$.data.askCounterpart[0]").value("교육비 반환 조건을 빼 주실 수 있나요?"))
                .andExpect(jsonPath("$.data.issueSummary").value(nullValue()))
                .andExpect(jsonPath("$.data.whyCheck").value(nullValue()))
                .andExpect(jsonPath("$.data.laws", hasSize(0)))
                .andExpect(jsonPath("$.data.relatedClauses[*].clauseId", contains(n(k1))))
                .andExpect(jsonPath("$.data.relatedClauses[0].title").value("계약 기간"))
                .andExpect(jsonPath("$.data.relatedClauses[0].originalText").value("계약기간: 2026.12.01 ~ 2027.02.28"));
    }

    @Test
    void OK_조항은_문제_요약과_확인_이유가_null() throws Exception {
        get(owner, "/api/analyses/" + done + "/clauses/" + k1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("OK"))
                .andExpect(jsonPath("$.data.issueSummary").value(nullValue()))
                .andExpect(jsonPath("$.data.whyCheck").value(nullValue()))
                .andExpect(jsonPath("$.data.laws", hasSize(0)));
    }

    @Test
    void 읽지_못한_조항은_원문이_null() throws Exception {
        get(owner, "/api/analyses/" + done + "/clauses/" + k5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("UNREADABLE"))
                .andExpect(jsonPath("$.data.title").value(nullValue()))
                .andExpect(jsonPath("$.data.original.text").value(nullValue()))
                .andExpect(jsonPath("$.data.original.pageNo").value(2));
    }

    @Test
    void 제외_판정_중_다른_분석의_없는_조항은_404_CLAUSE_NOT_FOUND() throws Exception {
        for (long clauseId : new long[]{k4, k6, othersClause, Long.MAX_VALUE}) {
            get(owner, "/api/analyses/" + done + "/clauses/" + clauseId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLAUSE_NOT_FOUND"));
        }
    }

    @Test
    void 완료되지_않은_분석의_조항은_409_ANALYSIS_NOT_COMPLETED() throws Exception {
        for (long id : new long[]{running, failed}) {
            get(owner, "/api/analyses/" + id + "/clauses/" + k1)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"));
        }
    }

    @Test
    void 조항_상세는_없거나_남의_것이거나_삭제된_계약서의_분석이면_404_ANALYSIS_NOT_FOUND() throws Exception {
        for (long id : new long[]{othersAnalysis, deletedAnalysis, Long.MAX_VALUE}) {
            get(owner, "/api/analyses/" + id + "/clauses/" + othersClause)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        }
        // 남의 분석은 그 주인에게는 보인다
        get(other, "/api/analyses/" + othersAnalysis + "/clauses/" + othersClause)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("남의 조항"));
    }

    // ---- 예시 분석 조회 ----

    @Test
    void 근로계약서_예시를_준다() throws Exception {
        get(owner, "/api/analyses/samples/EMPLOYMENT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(nullValue()))
                .andExpect(jsonPath("$.data.contractId").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.sample").value(true))
                .andExpect(jsonPath("$.data.result.documentTypeCode").value("EMPLOYMENT"))
                .andExpect(jsonPath("$.data.result.analyzedAt").value("2026-11-20T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.result.clauses", hasSize(11)))
                .andExpect(jsonPath("$.data.clauseDetails['1'].articleNo").value("제1조"))
                .andExpect(jsonPath("$.data.clauseDetails['11'].title").value("기타"));
    }

    @Test
    void 예시가_없는_문서_종류는_404_SAMPLE_NOT_FOUND() throws Exception {
        for (String code : new String[]{"LEASE", "employment", "NOPE"}) {
            get(owner, "/api/analyses/samples/" + code)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SAMPLE_NOT_FOUND"));
        }
    }

    // ---- 도우미 ----

    ResultActions get(UUID user, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", "Bearer " + tokenService.issue(user)));
    }

    /** jsonPath 는 작은 숫자를 Integer 로 읽는다 */
    static Number n(long v) {
        return v <= Integer.MAX_VALUE ? (Number) (int) v : (Number) v;
    }

    UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                id, String.format("%064x", System.nanoTime()));
        users.add(id);
        return id;
    }

    long newLaw(String name) {
        long id = jdbc.queryForObject("insert into law (name, name_norm, law_kind, source_law_id, synced_at)"
                + " values (?, ?, '법률', 'TEST', now()) returning law_id", Long.class, name, name);
        laws.add(id);
        return id;
    }

    long newArticle(long lawId, int articleNo, Integer paragraphNo, String title, String content) {
        return jdbc.queryForObject("insert into law_article (law_id, article_no, article_branch_no, paragraph_no, title,"
                + " content, source_mst, effective_from) values (?, ?, 0, ?, ?, ?, 'TEST', date '2025-01-01')"
                + " returning law_article_id", Long.class, lawId, articleNo, paragraphNo, title, content);
    }

    long newContract(UUID user, String title, boolean archived, boolean deleted) {
        long id = jdbc.queryForObject("insert into contract (user_id, document_type_code, title, archived,"
                + " current_version_no, archived_at, deleted_at, created_at, updated_at)"
                + " values (?, ?, ?, ?, 1, " + (archived ? "now()" : "null") + ", " + (deleted ? "now()" : "null")
                + ", now(), now()) returning contract_id", Long.class, user, typeCode, title, archived);
        contracts.add(id);
        return id;
    }

    long newVersion(long contractId, int versionNo, int pageCount) {
        long id = jdbc.queryForObject("insert into contract_version (contract_id, version_no, page_count, created_at,"
                + " updated_at) values (?, ?, ?, now(), now()) returning version_id", Long.class,
                contractId, versionNo, pageCount);
        versions.add(id);
        return id;
    }

    void newPage(long versionId, int pageNo, int masked) {
        jdbc.update("insert into contract_page (version_id, page_no, image_key, masked_count) values (?, ?, ?, ?)",
                versionId, pageNo, "test/" + UUID.randomUUID() + ".jpg", masked);
    }

    /** completedSql 은 SQL 식 (null 이면 null) */
    long newAnalysis(long versionId, String status, String step, String failReason, String detected,
                     String completedSql, Long similarContractId) {
        long id = jdbc.queryForObject("insert into analysis (version_id, document_type_code, status, progress_step,"
                + " fail_reason, detected_type_name, prompt_version, similar_contract_id, started_at, completed_at)"
                + " values (?, ?, ?, ?, ?, ?, 'test', ?, now(), " + (completedSql == null ? "null" : completedSql) + ")"
                + " returning analysis_id", Long.class, versionId, typeCode, status, step, failReason, detected,
                similarContractId);
        analyses.add(id);
        return id;
    }

    void newChunk(long analysisId, int chunkNo, int from, int to, String status) {
        jdbc.update("insert into analysis_chunk (analysis_id, chunk_no, page_from, page_to, status, retry_count)"
                + " values (?, ?, ?, ?, ?, 0)", analysisId, chunkNo, from, to, status);
    }

    long newClause(long analysisId, String key, String articleNo, String title, String text, int pageNo,
                   String verdict, String basisType, Integer priority, boolean excluded) {
        return jdbc.queryForObject("insert into analysis_clause (analysis_id, clause_key, article_no, title,"
                + " original_text, one_line, page_no, issue_found, verdict, basis_type, priority, excluded)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning clause_id", Long.class,
                analysisId, key, articleNo, title, text, articleNo + " 한 줄", pageNo, !"OK".equals(verdict),
                verdict, basisType, priority, excluded);
    }

    void newRef(long clauseId, String label, Long lawArticleId, Long guidance, String foundBy, boolean cited,
                String refText) {
        jdbc.update("insert into analysis_clause_ref (clause_id, ref_label, law_article_id, guidance_id, found_by, cited,"
                + " ref_title, ref_text) values (?, ?, ?, ?, ?, ?, '근거 제목', ?)",
                clauseId, label, lawArticleId, guidance, foundBy, cited, refText);
    }

    void newRelation(long analysisId, long clauseId, long relatedId) {
        jdbc.update("insert into analysis_clause_relation (analysis_id, clause_id, related_clause_id) values (?, ?, ?)",
                analysisId, clauseId, relatedId);
    }
}
