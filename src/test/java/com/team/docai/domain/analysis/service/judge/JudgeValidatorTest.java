package com.team.docai.domain.analysis.service.judge;

import com.team.docai.domain.analysis.dto.JudgeResponse;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.service.judge.JudgeValidator.Judged;
import com.team.docai.domain.analysis.service.judge.JudgeValidator.Result;
import com.team.docai.domain.analysis.service.judge.JudgeValidator.SentClause;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 아키텍처 8단계 검증 표 (인용, UNCERTAIN, 모르는 키, 규칙 충돌, 단정 표현, 길이) */
class JudgeValidatorTest {

    static final Set<String> ALL_KEYS = Set.of("c1", "c4", "c7", "c9");

    /** c7: 조문 L1·L2, 메모 G3 / c4: 규칙 결과 조항 (RULE 근거 L1, 꼬리표 근거 L2) / c9: 근거 없음 */
    static Map<String, SentClause> sent() {
        Map<String, SentClause> sent = new LinkedHashMap<>();
        sent.put("c7", new SentClause("c7", false, Set.of("L1", "L2", "G3"), Set.of("L1", "L2")));
        sent.put("c4", new SentClause("c4", true, Set.of("L1", "L2"), Set.of("L2")));
        sent.put("c9", new SentClause("c9", false, Set.of(), Set.of()));
        return sent;
    }

    static JudgeResponse.Judgment judgment(String key, String verdict, List<String> cited) {
        return new JudgeResponse.Judgment(key, verdict, "제목 " + key, "쉬운 설명.", "확인이 필요해요.", cited,
                List.of(), List.of("물어볼 것?"));
    }

    static Result validate(List<JudgeResponse.Judgment> judgments) {
        return JudgeValidator.validate(JudgeValidator.STAGE_JUDGE, new JudgeResponse(judgments, List.of(), List.of()),
                sent(), ALL_KEYS, true);
    }

    static List<String> types(Result r) {
        return r.log().stream().map(m -> (String) m.get("type")).toList();
    }

    @Test
    void 그_조항에_준_ID가_아닌_인용은_지우고_남은_인용만_남긴다() {
        Result r = validate(List.of(judgment("c7", "NEEDS_CHECK", List.of("L1", "L9", "L1", "G3", " L2 "))));

        Judged j = r.judgments().get("c7");
        assertThat(j.verdict()).isEqualTo(ClauseVerdict.NEEDS_CHECK);
        assertThat(j.citedLabels()).containsExactly("L1", "G3", "L2");
        assertThat(r.log()).anySatisfy(m -> {
            assertThat(m.get("type")).isEqualTo("CITATION_REMOVED");
            assertThat(m.get("target")).isEqualTo("clause c7.citedIds");
            assertThat(m.get("detail")).isEqualTo("L9");
            assertThat(m.get("stage")).isEqualTo("JUDGE");
        });
    }

    @Test
    void NEEDS_CHECK인데_남은_조문_인용이_없으면_UNCERTAIN으로_낮추고_인용을_비운다() {
        Result r = validate(List.of(
                judgment("c7", "NEEDS_CHECK", List.of("L5")),        // 준 ID 아님 → 지워져 0개
                judgment("c9", "NEEDS_CHECK", List.of())));          // 근거 0개 (꼬리표·근거 없는 의심 조항)

        assertThat(r.judgments().get("c7").verdict()).isEqualTo(ClauseVerdict.UNCERTAIN);
        assertThat(r.judgments().get("c7").citedLabels()).isEmpty();
        assertThat(r.judgments().get("c9").verdict()).isEqualTo(ClauseVerdict.UNCERTAIN);
        assertThat(types(r)).filteredOn("VERDICT_DOWNGRADED"::equals).hasSize(2);
    }

    @Test
    void 해석_메모만_인용한_확인_필요도_조문_인용이_없어_UNCERTAIN() {
        Result r = validate(List.of(judgment("c7", "NEEDS_CHECK", List.of("G3"))));

        assertThat(r.judgments().get("c7").verdict()).isEqualTo(ClauseVerdict.UNCERTAIN);
        assertThat(r.judgments().get("c7").citedLabels()).isEmpty();
    }

    @Test
    void 보낸_조항이_아닌_clauseKey의_판단은_버리고_오지_않은_조항은_기록한다() {
        Result r = validate(List.of(judgment("c1", "NEEDS_CHECK", List.of("L1")), judgment("c99", "OK", List.of()),
                judgment("c7", "OK", List.of()), judgment("c7", "NEEDS_CHECK", List.of("L1"))));

        assertThat(r.judgments()).containsOnlyKeys("c7");
        assertThat(r.judgments().get("c7").verdict()).isEqualTo(ClauseVerdict.OK);
        assertThat(types(r)).contains("JUDGMENT_UNKNOWN_KEY", "JUDGMENT_DUPLICATED", "JUDGMENT_MISSING");
        assertThat(r.log()).filteredOn(m -> "JUDGMENT_MISSING".equals(m.get("type")))
                .extracting(m -> m.get("target")).containsExactlyInAnyOrder("clause c4", "clause c9");
    }

    @Test
    void 규칙_결과_조항은_GPT가_OK나_UNCERTAIN이라_해도_NEEDS_CHECK이고_RULE_근거만_인용해도_낮추지_않는다() {
        Result ok = validate(List.of(judgment("c4", "OK", List.of("L1"))));
        Result uncertain = validate(List.of(judgment("c4", "UNCERTAIN", List.of())));
        Result ruleOnly = validate(List.of(judgment("c4", "NEEDS_CHECK", List.of("L1"))));

        assertThat(ok.judgments().get("c4").verdict()).isEqualTo(ClauseVerdict.NEEDS_CHECK);
        assertThat(ok.judgments().get("c4").whyCheck()).isEqualTo("확인이 필요해요.");
        assertThat(types(ok)).contains("RULE_OVERRIDE");
        assertThat(uncertain.judgments().get("c4").verdict()).isEqualTo(ClauseVerdict.NEEDS_CHECK);
        assertThat(ruleOnly.judgments().get("c4").verdict()).isEqualTo(ClauseVerdict.NEEDS_CHECK);
        assertThat(ruleOnly.judgments().get("c4").citedLabels()).containsExactly("L1");
        assertThat(types(ruleOnly)).doesNotContain("VERDICT_DOWNGRADED", "RULE_OVERRIDE");
    }

    @Test
    void 단정_표현이_든_문장과_항목은_지우고_지운_내용을_기록한다() {
        JudgeResponse.Judgment j = new JudgeResponse.Judgment("c7", "NEEDS_CHECK", "이 조항은 무효예요",
                "교육비를 돌려줘야 해요. 서명해도 됩니다.",
                "위약금을 미리 정한 조항이에요. 이 조항은 불법입니다! 계약 기간과 함께 확인이 필요해요.",
                List.of("L1"), List.of(), List.of("교육비 내역을 알려 주실 수 있나요?", "위법이니 빼 주세요."));
        Result r = JudgeValidator.validate(JudgeValidator.STAGE_JUDGE,
                new JudgeResponse(List.of(j), List.of(), List.of("서명하셔도 돼요?", "교육비 조항을 빼 주실 수 있나요?")),
                sent(), ALL_KEYS, true);

        Judged out = r.judgments().get("c7");
        assertThat(out.headline()).isNull();
        assertThat(out.easyExplanation()).isEqualTo("교육비를 돌려줘야 해요.");
        assertThat(out.whyCheck()).isEqualTo("위약금을 미리 정한 조항이에요. 계약 기간과 함께 확인이 필요해요.");
        assertThat(out.askCounterpart()).containsExactly("교육비 내역을 알려 주실 수 있나요?");
        assertThat(r.questions()).containsExactly("교육비 조항을 빼 주실 수 있나요?");
        assertThat(r.log()).filteredOn(m -> "ASSERTION_REMOVED".equals(m.get("type")))
                .extracting(m -> (String) m.get("detail"))
                .contains("'무효': 이 조항은 무효예요", "'불법': 이 조항은 불법입니다!", "'서명해도 됩니다': 서명해도 됩니다.",
                        "'위법': 위법이니 빼 주세요.", "'서명하셔도 돼요': 서명하셔도 돼요?");
    }

    @Test
    void 위반됩니다_위반돼요_효력이_없다_무효예요_같은_형태도_지운다() {
        String[] assertive = {"이 조항은 법에 위반됩니다.", "이건 위반돼요.", "위반이에요.", "이 약정은 효력이 없어요.",
                "이 조항은 무효예요.", "근로기준법을 위반하는 조항이에요.", "효력이없습니다."};
        for (String sentence : assertive) {
            assertThat(JudgeValidator.withoutAssertions("JUDGE", sentence, "t", new java.util.ArrayList<>()))
                    .as(sentence).isNull();
        }
        // 단정이 아닌 표현은 남긴다
        String soft = "위약금으로 볼 소지가 있어 확인이 필요해요. 위반 여부는 상대방 설명을 들어 봐야 해요.";
        assertThat(JudgeValidator.withoutAssertions("JUDGE", soft, "t", new java.util.ArrayList<>())).isEqualTo(soft);
    }

    @Test
    void UNCERTAIN_판단의_headline과_whyCheck에도_단정_표현_검사를_건다() {
        JudgeResponse.Judgment j = new JudgeResponse.Judgment("c9", "UNCERTAIN", "이 조항은 위반됩니다", null,
                "근거가 부족해요. 사실상 무효예요.", List.of(), List.of(), List.of());

        Result r = validate(List.of(j));

        Judged out = r.judgments().get("c9");
        assertThat(out.verdict()).isEqualTo(ClauseVerdict.UNCERTAIN);
        assertThat(out.headline()).isNull();
        assertThat(out.whyCheck()).isEqualTo("근거가 부족해요.");
        assertThat(r.log()).filteredOn(m -> "ASSERTION_REMOVED".equals(m.get("type")))
                .extracting(m -> m.get("target")).contains("clause c9.headline", "clause c9.whyCheck");
    }

    @Test
    void 사용자에게_보이는_글의_내부_조항_키는_조_번호나_제목으로_바꾸고_없는_키는_그대로_둔다() {
        Map<String, String> names = Map.of(
                "c5", JudgeValidator.displayName("제5조", "임금"),
                "c1", JudgeValidator.displayName(null, "임금"),
                "c4", JudgeValidator.displayName(" ", null));
        JudgeResponse.Judgment j = new JudgeResponse.Judgment("c7", "NEEDS_CHECK", "c5 임금 확인 필요",
                "c1에 적힌 내용이에요.",
                "c5에서 임금 세부 내용이 빠졌고, c1에 약정연장근로수당으로 적혀 있어요. c4, c99도 함께 보세요.",
                List.of("L1"), List.of(), List.of("C1 등급 기준을 알려 주실 수 있나요?"));
        Result r = JudgeValidator.validate(JudgeValidator.STAGE_JUDGE,
                new JudgeResponse(List.of(j), List.of(), List.of("c1 조항 내용을 알려 주실 수 있나요?")),
                sent(), ALL_KEYS, true, names);

        Judged out = r.judgments().get("c7");
        assertThat(out.headline()).isEqualTo("제5조 임금 확인 필요");
        assertThat(out.easyExplanation()).isEqualTo("「임금」 조항에 적힌 내용이에요.");
        assertThat(out.whyCheck()).isEqualTo("제5조에서 임금 세부 내용이 빠졌고, 「임금」 조항에 약정연장근로수당으로 적혀 있어요."
                + " 관련 조항, c99도 함께 보세요.");
        // 대문자 C1(등급)은 조항 키가 아니다
        assertThat(out.askCounterpart()).containsExactly("C1 등급 기준을 알려 주실 수 있나요?");
        // "c1 조항" → "「임금」 조항 조항" 이 되지 않는다
        assertThat(r.questions()).containsExactly("「임금」 조항 내용을 알려 주실 수 있나요?");
        assertThat(r.log()).filteredOn(m -> "CLAUSE_KEY_REPLACED".equals(m.get("type")))
                .extracting(m -> (String) m.get("detail"))
                .contains("c5 → 제5조", "c1 → 「임금」 조항", "c4 → 관련 조항", "c1 조항 → 「임금」 조항")
                .noneMatch(d -> d.startsWith("c99") || d.startsWith("C1"));
        assertThat(out.citedLabels()).containsExactly("L1");
    }

    @Test
    void 키_바꾸기는_목록_쉼표_가운뎃점_괄호_전각_문자_옆에서도_되고_영문_낱말_안은_건드리지_않는다() {
        Map<String, String> names = Map.of("c1", "제1조", "c2", "제2조", "c5", "제5조");
        List<Map<String, Object>> log = new java.util.ArrayList<>();
        assertThat(JudgeValidator.replaceClauseKeys("JUDGE", "c1·c2, c5,c1（c2）와 c05를 보세요", "t", names, log))
                .isEqualTo("제1조·제2조, 제5조,제1조（제2조）와 제5조를 보세요");
        assertThat(JudgeValidator.replaceClauseKeys("JUDGE", "abc1 c1a 1c2 etc3 abc_c4 c5_x 그대로예요.", "t", names,
                new java.util.ArrayList<>())).isEqualTo("abc1 c1a 1c2 etc3 abc_c4 c5_x 그대로예요.");
    }

    @Test
    void 말이_겹치지_않게_뒤따르는_조와_조항을_처리한다() {
        Map<String, String> names = Map.of("c5", "제5조", "c1", "「임금」 조항", "c3", "관련 조항");
        List<Map<String, Object>> log = new java.util.ArrayList<>();
        assertThat(JudgeValidator.replaceClauseKeys("JUDGE", "c5조와 c5 조항, c5조항", "t", names, log))
                .isEqualTo("제5조와 제5조 조항, 제5조 조항");
        assertThat(JudgeValidator.replaceClauseKeys("JUDGE", "c1 조항과 c3조항", "t", names, log))
                .isEqualTo("「임금」 조항과 관련 조항");
    }

    @Test
    void 이름으로_바꾼_뒤_칸_길이로_자른다() {
        String longTitle = "가".repeat(140);
        Map<String, String> names = Map.of("c1", JudgeValidator.displayName(null, longTitle));
        JudgeResponse.Judgment j = new JudgeResponse.Judgment("c7", "NEEDS_CHECK", "c1 확인 필요", null, null,
                List.of("L1"), List.of(), List.of());

        Result r = JudgeValidator.validate(JudgeValidator.STAGE_JUDGE, new JudgeResponse(List.of(j), List.of(), List.of()),
                sent(), ALL_KEYS, true, names);

        String headline = r.judgments().get("c7").headline();
        assertThat(headline).hasSize(150).startsWith("「가");
        assertThat(types(r)).containsSubsequence("CLAUSE_KEY_REPLACED", "TOO_LONG");
    }

    @Test
    void 조항_제목에_단정_표현_낱말이_있어도_AI_문장을_지우지_않는다() {
        Map<String, String> names = Map.of("c1", JudgeValidator.displayName(null, "무효 사유"));
        JudgeResponse.Judgment j = new JudgeResponse.Judgment("c7", "NEEDS_CHECK", null, null,
                "c1과 함께 확인이 필요해요.", List.of("L1"), List.of(), List.of("c1 내용을 알려 주실 수 있나요?"));

        Result r = JudgeValidator.validate(JudgeValidator.STAGE_JUDGE, new JudgeResponse(List.of(j), List.of(), List.of()),
                sent(), ALL_KEYS, true, names);

        assertThat(r.judgments().get("c7").whyCheck()).isEqualTo("「무효 사유」 조항과 함께 확인이 필요해요.");
        assertThat(r.judgments().get("c7").askCounterpart()).containsExactly("「무효 사유」 조항 내용을 알려 주실 수 있나요?");
        assertThat(types(r)).doesNotContain("ASSERTION_REMOVED");
    }

    @Test
    void AI가_쓴_짧은_칸은_칸_길이로_자르고_기록한다() {
        JudgeResponse.Judgment j = new JudgeResponse.Judgment("c7", "NEEDS_CHECK", "가".repeat(160), null, null,
                List.of("L1"), List.of("c1", "c7", "c50", "c1"), List.of("나".repeat(250), "a", "b", "c"));

        Result r = validate(List.of(j));

        Judged out = r.judgments().get("c7");
        assertThat(out.headline()).hasSize(150);
        assertThat(out.askCounterpart()).hasSize(3);
        assertThat(out.askCounterpart().get(0)).hasSize(200);
        assertThat(out.relatedKeys()).containsExactly("c1");
        assertThat(types(r)).contains("TOO_LONG", "ITEM_DROPPED", "RELATED_UNKNOWN");
    }

    @Test
    void OK_판단은_확인_이유와_인용을_남기지_않는다() {
        Result r = validate(List.of(judgment("c7", "OK", List.of("L1"))));

        assertThat(r.judgments().get("c7").whyCheck()).isNull();
        assertThat(r.judgments().get("c7").citedLabels()).isEmpty();
    }

    @Test
    void needMore는_보낸_조항만_받고_마지막_판단에서는_받지_않는다() {
        JudgeResponse response = new JudgeResponse(List.of(judgment("c9", "UNCERTAIN", List.of())),
                List.of(new JudgeResponse.NeedMore("c9", "수습 기간 최저임금 감액 기준", List.of("수습", " 감액 ", "수습")),
                        new JudgeResponse.NeedMore("c50", "x", List.of("y"))),
                List.of());

        Result first = JudgeValidator.validate(JudgeValidator.STAGE_JUDGE, response, sent(), ALL_KEYS, true);
        Result last = JudgeValidator.validate(JudgeValidator.STAGE_JUDGE_MORE, response, sent(), ALL_KEYS, false);

        assertThat(first.needMore()).hasSize(1);
        assertThat(first.needMore().get(0).clauseKey()).isEqualTo("c9");
        assertThat(first.needMore().get(0).keywords()).containsExactly("수습", "감액");
        assertThat(types(first)).contains("NEED_MORE_UNKNOWN_KEY");
        assertThat(last.needMore()).isEmpty();
        assertThat(types(last)).contains("NEED_MORE_IGNORED");
    }

    @Test
    void 단정_표현_문장을_지울_때_날짜와_소수점은_나누지_않는다() {
        java.util.List<java.util.Map<String, Object>> log = new java.util.ArrayList<>();
        assertThat(JudgeValidator.withoutAssertions(JudgeValidator.STAGE_JUDGE,
                "계약 시작일은 2026.11.12이고 인상률은 10.5%예요. 이 조항은 무효예요.", "clause c1.whyCheck", log))
                .isEqualTo("계약 시작일은 2026.11.12이고 인상률은 10.5%예요.");
        assertThat(log).hasSize(1);
    }

    @Test
    void 단정_표현_문장을_지울_때_붙은_마침표는_나누고_줄바꿈은_줄바꿈으로_잇는다() {
        java.util.List<java.util.Map<String, Object>> log = new java.util.ArrayList<>();
        assertThat(JudgeValidator.withoutAssertions(JudgeValidator.STAGE_JUDGE,
                "확인이 필요해요.그리고 이 조항은 무효예요.\n1. 이 계약은 불법이에요.\n교육비를 물어보세요.", "clause c1.whyCheck", log))
                .isEqualTo("확인이 필요해요.\n교육비를 물어보세요.");
        assertThat(log).hasSize(2);
    }
}
