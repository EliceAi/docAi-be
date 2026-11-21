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
                new ExtractResponse.PageRead(4, "PARTIAL", List.of("4쪽 아래 서명란 위 두 줄 흐림")),
                new ExtractResponse.PageRead(2, "PARTIAL", List.of("제4조 ② 마지막 줄 끝이 잘림")),
                new ExtractResponse.PageRead(1, "READ"), new ExtractResponse.PageRead(9, "UNREADABLE")));

        ExtractMerger.Result merged = merge(chunk(1, 1, 4, r));

        assertThat(merged.failure()).isNull();
        assertThat(merged.partialPages()).containsExactly(2, 4);
        assertThat(logTypes(merged)).contains("PAGE_STATE_MISSING", "PAGE_OUT_OF_RANGE");
    }

    @Test
    void PARTIAL인데_못_읽은_곳이_비면_READ로_낮추고_기록한다() {
        List<String> blank = new java.util.ArrayList<>();
        blank.add("  ");
        blank.add(null);
        ExtractResponse r = withPages(response(1, 3, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(1, "PARTIAL"),
                new ExtractResponse.PageRead(2, "PARTIAL", blank),
                new ExtractResponse.PageRead(3, "PARTIAL", null)));

        ExtractMerger.Result merged = merge(chunk(1, 1, 3, r));

        assertThat(merged.failure()).isNull();
        assertThat(merged.partialPages()).isEmpty();
        assertThat(logTypes(merged).stream().filter("PARTIAL_WITHOUT_PARTS"::equals)).hasSize(3);
        assertThat(pagesJson(merged)).allSatisfy(p -> assertThat(p)
                .containsEntry("readState", "READ").containsEntry("unreadableParts", List.of()));
    }

    @Test
    void PARTIAL에_못_읽은_곳이_있으면_PARTIAL로_남고_extraction에_담긴다() {
        ExtractResponse a = withPages(response(1, 2, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(1, "READ", List.of("쓸데없는 설명")),
                new ExtractResponse.PageRead(2, "PARTIAL", List.of("제4조 ② 마지막 줄 끝이 잘림", "c3 조항 아래 흐림"))));
        // 겹친 쪽 2: 다른 묶음이 READ 라고 해도 더 나쁜 PARTIAL 과 그 설명이 남는다
        ExtractResponse b = withPages(response(2, 3, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(2, "READ"),
                new ExtractResponse.PageRead(3, "PARTIAL", List.of("x".repeat(150)))));

        ExtractMerger.Result merged = merge(chunk(1, 1, 2, a), chunk(2, 2, 3, b));

        assertThat(merged.failure()).isNull();
        assertThat(merged.partialPages()).containsExactly(2, 3);
        List<Map<String, Object>> pages = pagesJson(merged);
        assertThat(pages.get(0)).containsEntry("readState", "READ").containsEntry("unreadableParts", List.of());
        assertThat(pages.get(1)).containsEntry("readState", "PARTIAL")
                .containsEntry("unreadableParts", List.of("제4조 ② 마지막 줄 끝이 잘림", "관련 조항 아래 흐림"));
        assertThat(pages.get(2)).containsEntry("unreadableParts", List.of("x".repeat(100)));
        assertThat(logTypes(merged)).contains("PARTS_IGNORED", "CLAUSE_KEY_REPLACED", "TOO_LONG")
                .doesNotContain("PARTIAL_WITHOUT_PARTS");
    }

    @Test
    void 못_읽은_곳이_너무_많으면_앞쪽만_남긴다() {
        List<String> many = java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(i -> i + "번째 줄 흐림").toList();
        ExtractResponse r = withPages(response(1, 1, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(1, "PARTIAL", many)));

        ExtractMerger.Result merged = merge(chunk(1, 1, 1, r));

        assertThat((List<?>) pagesJson(merged).get(0).get("unreadableParts")).hasSize(10);
        assertThat(logTypes(merged)).contains("TOO_MANY");
    }

    @Test
    void UNREADABLE_쪽은_못_읽은_곳_없이도_그대로_PAGE_UNREADABLE() {
        ExtractResponse r = withPages(response(1, 2, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(1, "PARTIAL", List.of("1쪽 맨 아래 줄 잘림")),
                new ExtractResponse.PageRead(2, "UNREADABLE")));

        ExtractMerger.Result merged = merge(chunk(1, 1, 2, r));

        assertThat(merged.failure()).isEqualTo(new ExtractMerger.Failure(AnalysisFailReason.PAGE_UNREADABLE, 2, null));
        assertThat(logTypes(merged)).doesNotContain("PARTIAL_WITHOUT_PARTS");
    }

    @Test
    void 겹친_쪽이_두_묶음_모두_PARTIAL이면_설명을_합치고_같은_글은_하나만() {
        ExtractResponse a = withPages(response(1, 2, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(1, "READ"),
                new ExtractResponse.PageRead(2, "PARTIAL", List.of("제4조 ② 마지막 줄 끝이 잘림", "2쪽 아래 흐림"))));
        ExtractResponse b = withPages(response(2, 3, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(2, "PARTIAL", List.of(" 2쪽 아래 흐림 ", "2쪽 오른쪽 위 접힘")),
                new ExtractResponse.PageRead(3, "READ")));

        ExtractMerger.Result merged = merge(chunk(1, 1, 2, a), chunk(2, 2, 3, b));

        assertThat(merged.partialPages()).containsExactly(2);
        assertThat(pagesJson(merged).get(1)).containsEntry("readState", "PARTIAL").containsEntry("unreadableParts",
                List.of("제4조 ② 마지막 줄 끝이 잘림", "2쪽 아래 흐림", "2쪽 오른쪽 위 접힘"));
        assertThat(logTypes(merged)).doesNotContain("TOO_MANY");
    }

    @Test
    void 겹친_쪽_설명을_합쳐_10개를_넘으면_10개만_남기고_기록한다() {
        List<String> first = java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(i -> "앞 묶음 " + i + "번째 줄 흐림").toList();
        List<String> second = java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(i -> "뒤 묶음 " + i + "번째 줄 흐림").toList();
        ExtractResponse a = withPages(response(1, 2, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(1, "READ"), new ExtractResponse.PageRead(2, "PARTIAL", first)));
        ExtractResponse b = withPages(response(2, 3, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(2, "PARTIAL", second), new ExtractResponse.PageRead(3, "READ")));

        ExtractMerger.Result merged = merge(chunk(1, 1, 2, a), chunk(2, 2, 3, b));

        @SuppressWarnings("unchecked")
        List<String> parts = (List<String>) pagesJson(merged).get(1).get("unreadableParts");
        assertThat(parts).hasSize(10).startsWith(first.toArray(new String[0])).contains("뒤 묶음 4번째 줄 흐림")
                .doesNotContain("뒤 묶음 5번째 줄 흐림");
        assertThat(logTypes(merged)).containsOnlyOnce("TOO_MANY");
    }

    @Test
    void 겹친_쪽이_한_묶음은_UNREADABLE_다른_묶음은_PARTIAL이면_PAGE_UNREADABLE() {
        ExtractResponse a = withPages(response(1, 2, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(1, "READ"), new ExtractResponse.PageRead(2, "UNREADABLE")));
        ExtractResponse b = withPages(response(2, 3, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(2, "PARTIAL", List.of("2쪽 가운데 문단 흐림")),
                new ExtractResponse.PageRead(3, "READ")));

        ExtractMerger.Result merged = merge(chunk(1, 1, 2, a), chunk(2, 2, 3, b));

        assertThat(merged.failure()).isEqualTo(new ExtractMerger.Failure(AnalysisFailReason.PAGE_UNREADABLE, 2, null));
    }

    @Test
    void 겹친_쪽이_한_묶음은_PARTIAL_빈_목록_다른_묶음은_PARTIAL_설명이면_PARTIAL로_남는다() {
        ExtractResponse a = withPages(response(1, 2, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(1, "READ"), new ExtractResponse.PageRead(2, "PARTIAL")));
        ExtractResponse b = withPages(response(2, 3, List.of(), List.of()), List.of(
                new ExtractResponse.PageRead(2, "PARTIAL", List.of("3쪽 가운데 문단 흐림")),
                new ExtractResponse.PageRead(3, "READ")));

        ExtractMerger.Result merged = merge(chunk(1, 1, 2, a), chunk(2, 2, 3, b));

        assertThat(merged.failure()).isNull();
        assertThat(merged.partialPages()).containsExactly(2);
        assertThat(pagesJson(merged).get(1)).containsEntry("readState", "PARTIAL")
                .containsEntry("unreadableParts", List.of("3쪽 가운데 문단 흐림"));
        assertThat(logTypes(merged)).containsOnlyOnce("PARTIAL_WITHOUT_PARTS");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> pagesJson(ExtractMerger.Result r) {
        return (List<Map<String, Object>>) r.extraction().get("pages");
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
                .containsEntry("promptVersion", "extract-v4")
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

    @Test
    void 근로시간의_시각과_휴게는_값이_있을_때만_남고_시각_형식을_맞추고_틀린_값은_버린다() {
        ExtractResponse.ValueJson v = new ExtractResponse.ValueJson(null, null, null, 4.0, 5.0, null, null, null,
                "9:00", "18:00", List.of(new ExtractResponse.TimeRange("12:00", "13:00"),
                new ExtractResponse.TimeRange("25:00", "13:00"), new ExtractResponse.TimeRange("15:00", "15:00")),
                0, true);
        ExtractResponse r = response(1, 1, List.of(clause("c1", 1, "제4조", "소정근로시간", "x")),
                List.of(written("근로시간", "09시 00분부터 18시 00분까지", "c1", "x", v),
                        written("임금", "시급 10,320원", "c1", "x",
                                new ExtractResponse.ValueJson("HOURLY", 10320L, null, null, null, null, null, null,
                                        "09:00", "18:00", List.of(), 30, true))));

        ExtractMerger.Result merged = merge(chunk(1, 1, 1, r));

        Map<String, ExtractMerger.MergedKeyTerm> byLabel = new java.util.HashMap<>();
        merged.keyTerms().forEach(t -> byLabel.put(t.label(), t));
        Map<String, Object> hours = new java.util.LinkedHashMap<>();
        hours.put("hoursPerDay", 4.0);
        hours.put("daysPerWeek", 5.0);
        hours.put("workStart", "09:00");
        hours.put("workEnd", "18:00");
        hours.put("breaks", List.of(Map.of("start", "12:00", "end", "13:00")));
        hours.put("noBreak", true);
        assertThat(byLabel.get("근로시간").valueJson()).isEqualTo(hours);
        // 임금 항목에는 근로시간 칸을 남기지 않는다
        assertThat(byLabel.get("임금").valueJson()).isEqualTo(Map.of("type", "HOURLY", "amount", 10320L));
        assertThat(merged.validationLog()).extracting(e -> e.get("type"))
                .contains("TIME_INVALID", "BREAK_INVALID");

        // 시각·휴게가 없으면 키도 넣지 않아 기존 모양 {hoursPerDay, daysPerWeek}
        ExtractResponse plain = response(1, 1, List.of(), List.of(written("근로시간", "1일 8시간", null, null,
                new ExtractResponse.ValueJson(null, null, 8.0, 5.0, null, null))));
        Map<String, Object> plainHours = merge(chunk(1, 1, 1, plain)).keyTerms().stream()
                .filter(t -> t.label().equals("근로시간")).findFirst().orElseThrow().valueJson();
        assertThat(plainHours).isEqualTo(Map.of("hoursPerDay", 8.0, "daysPerWeek", 5.0));
        assertThat(ExtractMerger.timeOrNull("24:00", "t", new java.util.ArrayList<>())).isEqualTo("24:00");
        assertThat(ExtractMerger.timeOrNull("14시", "t", new java.util.ArrayList<>())).isNull();
    }

    @Test
    void GPT가_지은_글의_조항_키는_관련_조항으로_바꾸고_계약서_원문과_계약서_값은_바꾸지_않는다() {
        ExtractResponse.Clause c = new ExtractResponse.Clause("c1", 1, "제1조", "c3 기준", "근로자는 c2 등급 업무를 맡는다.",
                "c2 조항과 함께 보는 계약기간", "c3 옆", false, false, true, "c2·c3에 적힌 금액을 물어냄", null,
                List.of(), List.of("위약금"), List.of());
        ExtractResponse r = withDates(response(1, 1, List.of(c),
                        List.of(written("업무", "c2 등급 업무", "c1", "근로자는 c2 등급 업무를 맡는다.", null))),
                null, List.of(new ExtractResponse.DateItem("c1 계약 시작일", "2026-12-01", "c1")));

        ExtractMerger.Result merged = merge(chunk(1, 1, 1, r));

        ExtractMerger.MergedClause out = merged.clauses().get(0);
        assertThat(out.oneLine()).isEqualTo("관련 조항과 함께 보는 계약기간");
        assertThat(out.location()).isEqualTo("관련 조항 옆");
        assertThat(out.issueSummary()).isEqualTo("관련 조항·관련 조항에 적힌 금액을 물어냄");
        // 계약서에서 온 값은 그대로
        assertThat(out.originalText()).isEqualTo("근로자는 c2 등급 업무를 맡는다.");
        assertThat(out.title()).isEqualTo("c3 기준");
        assertThat(merged.keyTerms()).filteredOn(t -> t.label().equals("업무"))
                .extracting(ExtractMerger.MergedKeyTerm::value, ExtractMerger.MergedKeyTerm::evidence)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("c2 등급 업무", "근로자는 c2 등급 업무를 맡는다."));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dates = (List<Map<String, Object>>) merged.extraction().get("dates");
        assertThat(dates.get(0).get("label")).isEqualTo("관련 조항 계약 시작일");
        assertThat(merged.validationLog()).filteredOn(m -> "CLAUSE_KEY_REPLACED".equals(m.get("type")))
                .extracting(m -> (String) m.get("target"))
                .contains("clause c1.oneLine", "clause c1.location", "clause c1.issueSummary", "dates.label")
                .doesNotContain("clause c1.originalText", "clause c1.title");
    }
}
