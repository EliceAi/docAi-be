package com.team.docai.domain.analysis.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.dto.ExtractResponse;
import com.team.docai.domain.analysis.entity.LawHint;
import com.team.docai.domain.law.dto.IssueTagInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 1차 출력 스키마 ↔ 응답 record ↔ 저장 칸 길이 */
class ExtractPromptTest {

    static final List<IssueTagInfo> TAGS = List.of(new IssueTagInfo("PENALTY_ON_RESIGNATION", "퇴사 위약금"));

    @Test
    @SuppressWarnings("unchecked")
    void 스키마의_칸은_응답_record와_1대1이고_strict_규칙을_지킨다() {
        Map<String, Object> schema = ExtractPrompt.schema("EMPLOYMENT", TAGS);

        assertMatches(schema, ExtractResponse.class, "$");
        Map<String, Object> clause = items(props(schema).get("clauses"));
        assertMatches(clause, ExtractResponse.Clause.class, "$.clauses");
        assertMatches(items(props(clause).get("lawHints")), LawHint.class, "$.clauses.lawHints");
        Map<String, Object> keyTerm = items(props(schema).get("keyTerms"));
        assertMatches(keyTerm, ExtractResponse.KeyTerm.class, "$.keyTerms");
        assertMatches((Map<String, Object>) props(keyTerm).get("valueJson"), ExtractResponse.ValueJson.class, "valueJson");
        assertMatches(items(props(schema).get("pages")), ExtractResponse.PageRead.class, "$.pages");
        assertMatches(items(props(schema).get("dates")), ExtractResponse.DateItem.class, "$.dates");
        assertMatches((Map<String, Object>) props(schema).get("typeCheck"), ExtractResponse.TypeCheck.class, "typeCheck");
    }

    @Test
    void 글자_칸_maxLength는_저장_칸_길이와_같다() {
        Map<String, Object> schema = ExtractPrompt.schema("EMPLOYMENT", TAGS);
        Map<String, Object> clause = props(items(props(schema).get("clauses")));
        Map<String, Object> keyTerm = props(items(props(schema).get("keyTerms")));

        assertThat(max(clause, "clauseKey")).isEqualTo(20);
        assertThat(max(clause, "articleNo")).isEqualTo(20);
        assertThat(max(clause, "title")).isEqualTo(100);
        assertThat(max(clause, "oneLine")).isEqualTo(200);
        assertThat(max(clause, "location")).isEqualTo(100);
        assertThat(max(keyTerm, "value")).isEqualTo(200);
        assertThat(max(keyTerm, "evidence")).isEqualTo(300);
        assertThat(max(props((Map<String, Object>) props(schema).get("typeCheck")), "detectedTypeName")).isEqualTo(50);
        // 원문은 TEXT 라 길이 제한 없음
        assertThat(((Map<?, ?>) clause.get("originalText")).containsKey("maxLength")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 꼬리표와_주요_조건_항목은_목록으로_고정한다() {
        Map<String, Object> schema = ExtractPrompt.schema("EMPLOYMENT", TAGS);
        Map<?, ?> tag = (Map<?, ?>) props(items(props(schema).get("clauses"))).get("issueTag");
        Map<?, ?> label = (Map<?, ?>) props(items(props(schema).get("keyTerms"))).get("label");

        assertThat((List<Object>) tag.get("enum")).containsExactly("PENALTY_ON_RESIGNATION", null);
        assertThat((List<Object>) label.get("enum")).containsExactly("계약기간", "근무장소", "업무", "근로시간", "휴일", "임금",
                "임금 지급일", "연차휴가");
        Map<?, ?> noTags = (Map<?, ?>) props(items(props(ExtractPrompt.schema("EMPLOYMENT", List.of())).get("clauses")))
                .get("issueTag");
        assertThat(noTags.get("type")).isEqualTo("null");
        assertThat(ExtractPrompt.systemPrompt("LEASE", TAGS)).contains("주택 임대차계약서 (LEASE)")
                .contains("PENALTY_ON_RESIGNATION: 퇴사 위약금").contains("보증금");
    }

    @Test
    void 스키마_모양의_예시_JSON을_응답_record로_엄격하게_읽을_수_있다() throws Exception {
        String json = """
                {"documentTitle":"표준근로계약서","contractDate":"2026-11-20",
                 "typeCheck":{"matchesSelected":true,"detectedTypeName":null},"wageOnlyDocument":false,
                 "pages":[{"pageNo":1,"readState":"READ"}],
                 "clauses":[{"clauseKey":"c1","pageNo":1,"articleNo":"제7조","title":null,"originalText":"원문",
                   "oneLine":"요약","location":"1쪽 아래","continuesFromPreviousPage":false,"continuesToNextPage":false,
                   "issueFound":true,"issueSummary":"의심","issueTag":"PENALTY_ON_RESIGNATION",
                   "lawHints":[{"law":"근로기준법","article":"제20조"}],"searchKeywords":["위약금"],"relatedClauseKeys":[]}],
                 "keyTerms":[{"label":"임금","state":"WRITTEN","value":"시급 10,000원",
                   "valueJson":{"type":"HOURLY","amount":10000,"basePay":9000,"hoursPerDay":null,"daysPerWeek":null,"monthlyHours":null,"start":null,"end":null},
                   "clauseKey":"c1","evidence":"시급 10,000원"}],
                 "dates":[{"label":"계약 시작일","date":"2026-12-01","clauseKey":"c1"}]}
                """;
        ObjectMapper strict = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

        ExtractResponse r = strict.readValue(json, ExtractResponse.class);

        assertThat(r.clauses().get(0).lawHints()).containsExactly(new LawHint("근로기준법", "제20조"));
        assertThat(r.keyTerms().get(0).valueJson().amount()).isEqualTo(10000L);
        assertThat(r.keyTerms().get(0).valueJson().basePay()).isEqualTo(9000L);
        assertThat(r.wageOnlyDocument()).isFalse();
        Map<String, Object> schema = ExtractPrompt.schema("EMPLOYMENT", TAGS);
        assertThat(props(schema).get("wageOnlyDocument")).isEqualTo(Map.of("type", "boolean"));
        assertThat(ExtractPrompt.systemPrompt("EMPLOYMENT", TAGS)).contains("wageOnlyDocument").contains("basePay")
                .contains("monthlyHours");
    }

    // ---- 도움 ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> props(Map<String, Object> object) {
        return (Map<String, Object>) object.get("properties");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> items(Object array) {
        return (Map<String, Object>) ((Map<String, Object>) array).get("items");
    }

    static Object max(Map<String, Object> props, String name) {
        return ((Map<?, ?>) props.get(name)).get("maxLength");
    }

    @SuppressWarnings("unchecked")
    static void assertMatches(Map<String, Object> object, Class<?> record, String path) {
        List<String> components = Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
        assertThat(object.get("type")).as(path).isEqualTo("object");
        assertThat(object.get("additionalProperties")).as(path).isEqualTo(false);
        assertThat(props(object).keySet()).as(path).containsExactlyElementsOf(components);
        assertThat((List<Object>) object.get("required")).as(path).containsExactlyElementsOf(components);
        for (RecordComponent c : record.getRecordComponents()) {
            Type type = c.getGenericType();
            boolean nullableInJava = !c.getType().isPrimitive();
            Object schemaType = ((Map<?, ?>) props(object).get(c.getName())).get("type");
            boolean nullableInSchema = schemaType instanceof List<?> l && l.contains("null") || "null".equals(schemaType);
            if (type instanceof ParameterizedType || nullableInSchema) {
                assertThat(nullableInJava).as(path + "." + c.getName() + " 은 null 을 받을 수 있어야 함").isTrue();
            }
        }
    }

    @Test
    void 직접_쓰는_글_칸에_clauseKey를_쓰지_말라고_하고_버전은_extract_v3() {
        org.assertj.core.api.Assertions.assertThat(ExtractPrompt.systemPrompt("EMPLOYMENT", java.util.List.of()))
                .contains("clauseKey(c1, c2 같은 키)를 쓰지 않는다", "조 번호(예: \"제5조\")");
        org.assertj.core.api.Assertions.assertThat(ExtractPrompt.VERSION).isEqualTo("extract-v3");
    }
}
