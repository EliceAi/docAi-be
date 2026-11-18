package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.ExtractResponse;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.team.docai.domain.analysis.service.ExtractFixtures.clause;
import static com.team.docai.domain.analysis.service.ExtractFixtures.continuing;
import static com.team.docai.domain.analysis.service.ExtractFixtures.issue;
import static com.team.docai.domain.analysis.service.ExtractFixtures.response;
import static com.team.docai.domain.analysis.service.ExtractFixtures.state;
import static com.team.docai.domain.analysis.service.ExtractFixtures.wage;
import static com.team.docai.domain.analysis.service.ExtractFixtures.withDates;
import static com.team.docai.domain.analysis.service.ExtractFixtures.withPages;
import static com.team.docai.domain.analysis.service.ExtractFixtures.withTexts;
import static com.team.docai.domain.analysis.service.ExtractFixtures.withType;
import static com.team.docai.domain.analysis.service.ExtractFixtures.written;
import static org.assertj.core.api.Assertions.assertThat;

class ExtractMergerTest {

    static final String TYPE = "EMPLOYMENT";
    static final Set<String> TAGS = Set.of("PENALTY_ON_RESIGNATION", "MIN_WAGE_SHORTFALL");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    static ExtractMerger.ChunkResult chunk(int no, int from, int to, ExtractResponse r) {
        return new ExtractMerger.ChunkResult(no, from, to, r);
    }

    static ExtractMerger.Result merge(ExtractMerger.ChunkResult... chunks) {
        return ExtractMerger.merge(TYPE, List.of(chunks), TAGS, TODAY);
    }

    static List<String> logTypes(ExtractMerger.Result r) {
        return r.validationLog().stream().map(m -> (String) m.get("type")).toList();
    }

    @Test
    void 묶음_경계의_조항을_하나로_합치고_키를_쪽_순서로_새로_매기며_관련_조항과_주요_조건을_새_키로_바꾼다() {
        ExtractResponse first = response(1, 5, List.of(
                clause("c1", 1, "제1조", "계약기간", "계약기간은 ..."),
                continuing(clause("c2", 5, "제7조", "교육비 반환", "근로자가 1년 이내 퇴사하면"), false, true)),
                List.of(state("임금", "MISSING")));
        ExtractResponse second = response(6, 7, List.of(
                continuing(clause("c1", 6, null, null, "교육비 30만 원을 반환한다."), true, false),
                issue(clause("c2", 7, "제8조", "임금", "시급 10,000원"), "MIN_WAGE_SHORTFALL", List.of("c1"))),
                List.of(written("임금", "시급 10,000원", "c2", "시급 10,000원", wage("HOURLY", 10000))));

        ExtractMerger.Result r = merge(chunk(2, 6, 7, second), chunk(1, 1, 5, first));

        assertThat(r.failure()).isNull();
        assertThat(r.clauses()).extracting(ExtractMerger.MergedClause::clauseKey,
                        ExtractMerger.MergedClause::articleNo, ExtractMerger.MergedClause::pageNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("c1", "제1조", 1),
                        org.assertj.core.groups.Tuple.tuple("c2", "제7조", 5),
                        org.assertj.core.groups.Tuple.tuple("c3", "제8조", 7));
        assertThat(r.clauses().get(1).originalText()).isEqualTo("근로자가 1년 이내 퇴사하면\n교육비 30만 원을 반환한다.");
        // 뒤 묶음의 c2 → 뒤 묶음의 c1 은 합쳐진 앞 조항(c2)을 가리킨다
        assertThat(r.relations()).containsExactly(new ExtractMerger.Relation("c3", "c2"));
        ExtractMerger.MergedKeyTerm wageTerm = r.keyTerms().stream().filter(t -> t.label().equals("임금")).findFirst().orElseThrow();
        assertThat(wageTerm.clauseKey()).isEqualTo("c3");
        assertThat(wageTerm.state()).isEqualTo(KeyTermState.OK);
        assertThat(wageTerm.source()).isEqualTo(KeyTermSource.WRITTEN);
        assertThat(wageTerm.valueJson()).isEqualTo(Map.of("type", "HOURLY", "amount", 10000L));
        assertThat(logTypes(r)).contains("BOUNDARY_MERGED");
        assertThat(r.clauseIndex()).extracting(m -> m.get("clauseKey")).containsExactly("c1", "c2", "c3");
    }

    @Test
    void GPT가_쪽_순서를_섞어_줘도_쪽_순서로_키를_매긴다() {
        ExtractResponse r = response(1, 2, List.of(
                clause("c1", 2, "제3조", "업무", "업무는 ..."),
                clause("c2", 1, "제1조", "계약기간", "계약기간 ..."),
                clause("c3", 1, "제2조", "근무장소", "근무장소 ...")), List.of());

        ExtractMerger.Result merged = merge(chunk(1, 1, 2, r));

        assertThat(merged.clauses()).extracting(ExtractMerger.MergedClause::articleNo)
                .containsExactly("제1조", "제2조", "제3조");
        assertThat(merged.clauses()).extracting(ExtractMerger.MergedClause::clauseKey).containsExactly("c1", "c2", "c3");
    }

    @Test
    void 겹친_쪽에서_두_번_나온_조항은_하나만_남긴다() {
        ExtractResponse a = response(1, 3, List.of(clause("c1", 3, "제5조", "휴일", "주휴일은 일요일")), List.of());
        ExtractResponse b = response(3, 4, List.of(
                clause("c1", 3, "제5조", "휴일", "주휴일은  일요일"),
                clause("c2", 4, "제6조", "임금", "월 250만 원")), List.of());

        ExtractMerger.Result r = merge(chunk(1, 1, 3, a), chunk(2, 3, 4, b));

        assertThat(r.clauses()).extracting(ExtractMerger.MergedClause::articleNo).containsExactly("제5조", "제6조");
        assertThat(logTypes(r)).contains("DUPLICATE_REMOVED");
    }

    @Test
    void 관련_조항은_자기_자신과_없는_키를_빼고_같은_쌍은_하나만() {
        ExtractResponse r = response(1, 1, List.of(
                issue(clause("c1", 1, "제1조", "a", "a"), null, List.of("c2", "c1", "c9")),
                issue(clause("c2", 1, "제2조", "b", "b"), null, List.of("c1"))), List.of());

        ExtractMerger.Result merged = merge(chunk(1, 1, 1, r));

        assertThat(merged.relations()).containsExactly(new ExtractMerger.Relation("c1", "c2"));
        assertThat(logTypes(merged)).contains("KEY_UNKNOWN");
    }

    @Test
    void AI가_쓴_글은_잘라서_저장하고_계약서에서_온_값은_자르지_않고_null이나_NEEDS_CHECK로_기록한다() {
        String longOneLine = "가".repeat(250);
        String longLocation = "나".repeat(120);
        String longTitle = "다".repeat(101);
        String longArticle = "제" + "1".repeat(25) + "조";
        String longText = "원문".repeat(3000);
        String longValue = "라".repeat(201);
        String longEvidence = "마".repeat(301);
        ExtractResponse r = response(1, 1, List.of(
                withTexts(clause("c1", 1, longArticle, longTitle, longText), longOneLine, longLocation)),
                List.of(written("임금", longValue, "c1", "근거", wage("MONTHLY", 2500000)),
                        written("휴일", "주휴일 일요일", "c1", longEvidence, null)));

        ExtractMerger.Result merged = merge(chunk(1, 1, 1, r));

        ExtractMerger.MergedClause c = merged.clauses().get(0);
        assertThat(c.oneLine()).isEqualTo("가".repeat(200));
        assertThat(c.location()).isEqualTo("나".repeat(100));
        assertThat(c.title()).isNull();
        assertThat(c.articleNo()).isNull();
        assertThat(c.originalText()).isEqualTo(longText);
        ExtractMerger.MergedKeyTerm wageTerm = merged.keyTerms().stream().filter(t -> t.label().equals("임금")).findFirst().orElseThrow();
        assertThat(wageTerm.value()).isNull();
        assertThat(wageTerm.state()).isEqualTo(KeyTermState.NEEDS_CHECK);
        assertThat(wageTerm.valueJson()).isEqualTo(Map.of("type", "MONTHLY", "amount", 2500000L));
        ExtractMerger.MergedKeyTerm holiday = merged.keyTerms().stream().filter(t -> t.label().equals("휴일")).findFirst().orElseThrow();
        assertThat(holiday.value()).isEqualTo("주휴일 일요일");
        assertThat(holiday.evidence()).isNull();
        assertThat(holiday.state()).isEqualTo(KeyTermState.OK);

        List<String> targets = merged.validationLog().stream().map(m -> (String) m.get("target")).toList();
        assertThat(targets).contains("clause c1.oneLine", "clause c1.location", "clause c1.title",
                "clause c1.articleNo", "keyTerm 임금.value", "keyTerm 휴일.evidence");
        // 기록에는 원문 글을 남기지 않는다 (길이만)
        assertThat(merged.validationLog().toString()).doesNotContain(longValue).doesNotContain("가".repeat(50));
    }

    @Test
    void 목록에_없는_꼬리표는_null로_바꾸고_기록한다() {
        ExtractResponse r = response(1, 1, List.of(
                issue(clause("c1", 1, "제1조", "a", "a"), "PENALTY_ON_RESIGNATION", List.of()),
                issue(clause("c2", 1, "제2조", "b", "b"), "MADE_UP_TAG", List.of())), List.of());

        ExtractMerger.Result merged = merge(chunk(1, 1, 1, r));

        assertThat(merged.clauses()).extracting(ExtractMerger.MergedClause::issueTagCode)
                .containsExactly("PENALTY_ON_RESIGNATION", null);
        assertThat(merged.validationLog()).anySatisfy(m -> {
            assertThat(m.get("type")).isEqualTo("TAG_DROPPED");
            assertThat(m.get("target")).isEqualTo("clause c2.issueTag");
            assertThat(m.get("detail")).isEqualTo("MADE_UP_TAG");
            assertThat(m.get("stage")).isEqualTo("EXTRACT");
        });
        assertThat(merged.clauses().get(0).searchKeywords()).containsExactly("위약금", "교육비");
    }

    @Test
    void 첫_쪽_묶음이_다른_종류라고_하면_TYPE_MISMATCH() {
        ExtractResponse r = withType(response(1, 2, List.of(), List.of()),
                new ExtractResponse.TypeCheck(false, "주택 임대차계약서"));

        ExtractMerger.Result merged = merge(chunk(1, 1, 2, r));

        assertThat(merged.failure()).isEqualTo(new ExtractMerger.Failure(AnalysisFailReason.TYPE_MISMATCH, null,
                "주택 임대차계약서"));
        assertThat(merged.clauses()).isEmpty();
    }

    @Test
    void 못_읽은_쪽이_있으면_가장_앞_쪽으로_PAGE_UNREADABLE() {
        ExtractResponse a = withPages(response(1, 5, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(1, "READ"), new ExtractResponse.PageRead(2, "PARTIAL"),
                new ExtractResponse.PageRead(3, "READ"), new ExtractResponse.PageRead(4, "UNREADABLE"),
                new ExtractResponse.PageRead(5, "READ")));
        ExtractResponse b = withPages(response(6, 6, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(6, "UNREADABLE")));

        ExtractMerger.Result merged = merge(chunk(2, 6, 6, b), chunk(1, 1, 5, a));

        assertThat(merged.failure()).isEqualTo(new ExtractMerger.Failure(AnalysisFailReason.PAGE_UNREADABLE, 4, null));
    }

    @Test
    void 일부만_읽은_쪽은_partialPages로_빠진_쪽_상태는_READ로_보고_기록한다() {
        ExtractResponse r = withPages(response(1, 4, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(4, "PARTIAL"), new ExtractResponse.PageRead(2, "PARTIAL"),
                new ExtractResponse.PageRead(1, "READ"), new ExtractResponse.PageRead(9, "UNREADABLE")));

        ExtractMerger.Result merged = merge(chunk(1, 1, 4, r));

        assertThat(merged.failure()).isNull();
        assertThat(merged.partialPages()).containsExactly(2, 4);
        assertThat(logTypes(merged)).contains("PAGE_STATE_MISSING", "PAGE_OUT_OF_RANGE");
    }

    @Test
    void 주요_조건_병합_값_있음_우선_값_다르면_앞쪽_값과_NEEDS_CHECK_값_없음끼리는_UNREADABLE_MASKED_MISSING() {
        ExtractResponse a = response(1, 5, List.of(clause("c1", 2, "제6조", "임금", "월 250만 원")), List.of(
                state("임금", "MISSING"),
                written("근무장소", "본사", "c1", "근무장소: 본사", null),
                state("휴일", "MISSING"),
                state("업무", "MASKED")));
        ExtractResponse b = response(6, 8, List.of(clause("c1", 6, "제9조", "임금 지급", "월 250만 원")), List.of(
                written("임금", "월 2,500,000원", "c1", "월 2,500,000원", wage("MONTHLY", 2500000)),
                written("근무장소", "지점", null, "근무장소: 지점", null),
                state("휴일", "UNREADABLE"),
                state("업무", "MISSING")));

        ExtractMerger.Result merged = merge(chunk(1, 1, 5, a), chunk(2, 6, 8, b));

        Map<String, ExtractMerger.MergedKeyTerm> byLabel = new java.util.HashMap<>();
        merged.keyTerms().forEach(t -> byLabel.put(t.label(), t));
        assertThat(byLabel.get("임금").state()).isEqualTo(KeyTermState.OK);
        assertThat(byLabel.get("임금").clauseKey()).isEqualTo("c2");
        assertThat(byLabel.get("근무장소").value()).isEqualTo("본사");
        assertThat(byLabel.get("근무장소").state()).isEqualTo(KeyTermState.NEEDS_CHECK);
        assertThat(byLabel.get("근무장소").evidence()).contains("근무장소: 본사").contains("'지점'로도 적혀 있음");
        assertThat(byLabel.get("휴일").state()).isEqualTo(KeyTermState.UNREADABLE);
        assertThat(byLabel.get("휴일").source()).isNull();
        assertThat(byLabel.get("업무").state()).isEqualTo(KeyTermState.MASKED);
        // 응답에 없던 항목도 MISSING 행으로 남는다 (항목마다 한 행, 정해진 순서)
        assertThat(merged.keyTerms()).extracting(ExtractMerger.MergedKeyTerm::label)
                .containsExactly("계약기간", "근무장소", "업무", "근로시간", "휴일", "임금", "임금 지급일", "연차휴가");
        assertThat(byLabel.get("계약기간").state()).isEqualTo(KeyTermState.MISSING);
        assertThat(merged.keyTerms()).extracting(ExtractMerger.MergedKeyTerm::sortOrder)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void value_json은_항목에_정해진_칸만_남긴다() {
        ExtractResponse r = response(1, 1, List.of(), List.of(
                written("근로시간", "09:00~18:00", null, null,
                        new ExtractResponse.ValueJson("HOURLY", 99L, 8.0, 5.0, "2026-12-01", null)),
                written("계약기간", "2026.12.01부터", null, null,
                        new ExtractResponse.ValueJson(null, null, null, null, "2026-12-01", "기간 없음")),
                written("업무", "디자인", null, null, new ExtractResponse.ValueJson(null, 1L, null, null, null, null))));

        ExtractMerger.Result merged = merge(chunk(1, 1, 1, r));

        Map<String, ExtractMerger.MergedKeyTerm> byLabel = new java.util.HashMap<>();
        merged.keyTerms().forEach(t -> byLabel.put(t.label(), t));
        assertThat(byLabel.get("근로시간").valueJson()).isEqualTo(Map.of("hoursPerDay", 8.0, "daysPerWeek", 5.0));
        Map<String, Object> period = new java.util.LinkedHashMap<>();
        period.put("start", "2026-12-01");
        period.put("end", null);
        assertThat(byLabel.get("계약기간").valueJson()).isEqualTo(period);
        assertThat(byLabel.get("업무").valueJson()).isNull();
        assertThat(logTypes(merged)).contains("DATE_INVALID");
    }

    @Test
    void 법_적용_기준일은_계약일_없으면_오늘이고_날짜는_새_키로() {
        ExtractResponse withDate = withDates(response(1, 1, List.of(clause("c1", 1, "제1조", "계약기간", "x")), List.of()),
                "2026-11-20", List.of(new ExtractResponse.DateItem("계약 시작일", "2026-12-01", "c1"),
                        new ExtractResponse.DateItem("이상한 날", "12월 1일", null)));

        ExtractMerger.Result merged = merge(chunk(1, 1, 1, withDate));
        ExtractMerger.Result noDate = merge(chunk(1, 1, 1, response(1, 1, List.of(), List.of())));

        assertThat(merged.lawBaseDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(noDate.lawBaseDate()).isEqualTo(TODAY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dates = (List<Map<String, Object>>) merged.extraction().get("dates");
        assertThat(dates).hasSize(1);
        assertThat(dates.get(0)).containsEntry("clauseKey", "c1").containsEntry("date", "2026-12-01");
        assertThat(merged.extraction()).containsEntry("contractDate", "2026-11-20")
                .containsEntry("promptVersion", "extract-v2")
                .containsEntry("wageOnlyDocument", false);
    }

    @Test
    void 원문을_못_읽은_조항은_원문_null로_남는다() {
        ExtractResponse r = response(1, 1, List.of(clause("c1", 1, "제1조", "a", null)), List.of());

        assertThat(merge(chunk(1, 1, 1, r)).clauses().get(0).originalText()).isNull();
    }

    @Test
    void 값_충돌_메모는_원문_인용을_자르지_않고_남는_길이에_맞춰_붙인다() {
        String quote = "가".repeat(280);
        ExtractResponse a = response(1, 1, List.of(), List.of(written("근무장소", "본사", null, quote, null)));
        ExtractResponse b = response(2, 2, List.of(), List.of(written("근무장소", "지점", null, "지점", null)));
        ExtractResponse c = response(1, 1, List.of(), List.of(written("근무장소", "본사", null, "가".repeat(301), null)));

        ExtractMerger.MergedKeyTerm fits = merge(chunk(1, 1, 1, a), chunk(2, 2, 2, b)).keyTerms().get(1);
        ExtractMerger.MergedKeyTerm quoteTooLong = merge(chunk(1, 1, 1, c), chunk(2, 2, 2, b)).keyTerms().get(1);

        assertThat(fits.evidence()).startsWith(quote + " (다른 쪽에").hasSize(300);
        assertThat(fits.state()).isEqualTo(KeyTermState.NEEDS_CHECK);
        // 원문 인용이 300자를 넘으면 인용은 버리고 서버 메모만 남는다
        assertThat(quoteTooLong.evidence()).isEqualTo("다른 쪽에 '지점'로도 적혀 있음");
        assertThat(ExtractMerger.appendNote("가".repeat(299), "메모", 300)).isEqualTo("가".repeat(299));
    }

    @Test
    void 못_읽은_쪽이_있으면_종류가_다르다고_해도_PAGE_UNREADABLE이_먼저() {
        ExtractResponse r = withType(withPages(response(1, 2, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(1, "UNREADABLE"), new ExtractResponse.PageRead(2, "READ"))),
                new ExtractResponse.TypeCheck(false, "알 수 없음"));

        assertThat(merge(chunk(1, 1, 2, r)).failure())
                .isEqualTo(new ExtractMerger.Failure(AnalysisFailReason.PAGE_UNREADABLE, 1, null));
    }

    @Test
    void 조_번호의_공백만_없앤다() {
        ExtractResponse r = response(1, 1, List.of(clause("c1", 1, "제 1조", "a", "a"),
                clause("c2", 1, "제 10 조 의 2", "b", "b")), List.of());

        assertThat(merge(chunk(1, 1, 1, r)).clauses()).extracting(ExtractMerger.MergedClause::articleNo)
                .containsExactly("제1조", "제10조의2");
        assertThat(ExtractMerger.normalizeArticleNo(null)).isNull();
    }

    @Test
    void 임금_basePay와_근로시간_monthlyHours는_값이_있을_때만_남고_임금_전용_문서_표시를_extraction에_남긴다() {
        ExtractResponse wageDoc = new ExtractResponse("연봉계약서", null, new ExtractResponse.TypeCheck(true, null), true,
                List.of(new ExtractResponse.PageRead(1, "READ")), List.of(clause("c1", 1, "제3조", "연봉", "x")),
                List.of(written("임금", "월 3,000,000원 (기본급 2,400,000원, 약정연장근로수당 600,000원)", "c1", "x",
                                new ExtractResponse.ValueJson("MONTHLY", 3000000L, 2400000L, null, null, null, null, null)),
                        written("근로시간", "통상임금 기준시간 수 209", "c1", "x",
                                new ExtractResponse.ValueJson(null, null, null, null, null, 209.0, null, null))),
                List.of());
        ExtractResponse chunk2 = response(2, 2, List.of(), List.of());

        ExtractMerger.Result merged = merge(chunk(1, 1, 1, wageDoc), chunk(2, 2, 2, chunk2));

        Map<String, ExtractMerger.MergedKeyTerm> byLabel = new java.util.HashMap<>();
        merged.keyTerms().forEach(t -> byLabel.put(t.label(), t));
        assertThat(byLabel.get("임금").valueJson())
                .isEqualTo(Map.of("type", "MONTHLY", "amount", 3000000L, "basePay", 2400000L));
        Map<String, Object> hours = new java.util.LinkedHashMap<>();
        hours.put("hoursPerDay", null);
        hours.put("daysPerWeek", null);
        hours.put("monthlyHours", 209.0);
        assertThat(byLabel.get("근로시간").valueJson()).isEqualTo(hours);
        assertThat(byLabel.get("근로시간").state()).isEqualTo(KeyTermState.OK);
        assertThat(merged.extraction()).containsEntry("wageOnlyDocument", true);
        // basePay 가 없으면 키도 넣지 않아 기존 모양 {type, amount}
        ExtractResponse plain = response(1, 1, List.of(), List.of(written("임금", "월 250만", null, null, wage("MONTHLY", 2500000))));
        assertThat(merge(chunk(1, 1, 1, plain)).keyTerms().get(5).valueJson()).isEqualTo(Map.of("type", "MONTHLY", "amount", 2500000L));
    }
}
