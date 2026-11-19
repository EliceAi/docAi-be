package com.team.docai.domain.analysis.service.judge;

import com.team.docai.domain.analysis.dto.JudgeResponse;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.service.AssertionText;
import com.team.docai.domain.analysis.service.ClauseKeyText;
import com.team.docai.global.util.TextTruncator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2차 응답 검증 (아키텍처 8단계, ERD 설계 결정 '검증 규칙'). DB·GPT 를 쓰지 않는 순수 계산.
 * <ol>
 *   <li>보낸 조항이 아닌 clauseKey 의 판단은 버린다. 같은 키가 두 번 오면 앞의 것만.</li>
 *   <li>citedIds 가 그 조항에 준 근거 ID 가 아니면 그 인용을 지운다.</li>
 *   <li>규칙 결과(basis_type = RULE)로 고정된 조항은 GPT 판단과 상관없이 NEEDS_CHECK (규칙 결과 우선).</li>
 *   <li>NEEDS_CHECK 인데 남은 인용 중 조문 근거(RULE 근거 제외)가 없으면 UNCERTAIN 으로 낮춘다.
 *       "법적 판단은 DB 에서 확인된 조문이 있을 때만"(아키텍처 핵심 원칙)이라 해석 메모(G)만 인용한 것은 세지 않는다.</li>
 *   <li>단정 표현("무효", "불법", "위법", "효력이 없다", "위반이다·위반됩니다·위반돼요", "서명해도 된다" 등)이 든 문장·항목은
 *       판정과 상관없이(UNCERTAIN 의 headline·whyCheck 포함) 지운다.</li>
 *   <li>AI 가 쓴 짧은 칸은 칸 길이로 자른다 (headline 150자, 목록 항목 200자).</li>
 *   <li>관련 조항은 목차에 있는 다른 조항 키만.</li>
 *   <li>사용자에게 보이는 글(headline, easyExplanation, whyCheck, askCounterpart, questionsBeforeSigning)에 내부 조항 키(c5 등)가
 *       나오면 그 조항의 조 번호("제5조"), 없으면 "「제목」 조항", 둘 다 없으면 "관련 조항"으로 바꾼다. 이 분석에 없는 키와
 *       영문 단어 안의 c1 은 그대로 둔다 ({@link ClauseKeyText}). 순서는 단정 표현 지우기 → 키 바꾸기 → 길이 자르기.</li>
 * </ol>
 * 지운 인용, 낮춘 판정, 지운 표현(내용 포함), 자른 칸, 버린 판단은 모두 log 에 남긴다 (analysis.validation_log_json 의 "judge").
 */
final class JudgeValidator {

    static final String STAGE_JUDGE = "JUDGE";
    static final String STAGE_JUDGE_MORE = "JUDGE_MORE";

    /** 단정 표현. 지시문에서 쓰지 말라고 했지만 지켜진다는 보장이 없어 서버가 한 번 더 거른다 */
    static final Pattern ASSERTION = AssertionText.ASSERTION;
    static final String UNKNOWN_CLAUSE_NAME = "관련 조항";

    static final int MAX_ASK = 3;
    static final int MAX_QUESTIONS = 5;
    static final int MAX_KEYWORDS = 5;
    static final int LOG_TEXT_MAX = 200;

    /**
     * 보낸 조항 하나.
     *
     * @param ruleBased       basis_type = RULE (숫자 규칙으로 고정)
     * @param labels          이 조항에 준 근거 ID 전부
     * @param lawLabels       인용 수로 세는 근거 ID: 조문 근거 중 RULE 이 아닌 것
     */
    record SentClause(String clauseKey, boolean ruleBased, Set<String> labels, Set<String> lawLabels) {
    }

    /** 검증을 통과한 판단 하나 */
    record Judged(String clauseKey, ClauseVerdict verdict, String headline, String easyExplanation, String whyCheck,
                  List<String> citedLabels, List<String> relatedKeys, List<String> askCounterpart) {
    }

    record NeedMoreRequest(String clauseKey, String want, List<String> keywords) {
    }

    record Result(Map<String, Judged> judgments, List<NeedMoreRequest> needMore, List<String> questions,
                  List<Map<String, Object>> log) {
    }

    private JudgeValidator() {
    }

    /**
     * @param sent           이 호출에 보낸 조항 (키 → 정보)
     * @param allClauseKeys  이 분석의 모든 조항 키 (관련 조항 확인용, 제외된 조항 빼고)
     * @param allowNeedMore  false 면 needMore 를 받지 않는다 (추가 조회는 분석당 1회)
     */
    static Result validate(String stage, JudgeResponse response, Map<String, SentClause> sent,
                           Set<String> allClauseKeys, boolean allowNeedMore) {
        return validate(stage, response, sent, allClauseKeys, allowNeedMore, Map.of());
    }

    /**
     * @param clauseNames 조항 키 → 화면에 쓸 이름 ({@link #displayName}). 글 속 내부 키를 바꿀 때 쓴다. 없는 키는 "관련 조항"
     */
    static Result validate(String stage, JudgeResponse response, Map<String, SentClause> sent,
                           Set<String> allClauseKeys, boolean allowNeedMore, Map<String, String> clauseNames) {
        List<Map<String, Object>> log = new ArrayList<>();
        Map<String, Judged> judged = new LinkedHashMap<>();
        for (JudgeResponse.Judgment j : nullSafe(response.judgments())) {
            String key = j.clauseKey() == null ? "" : j.clauseKey().trim();
            SentClause clause = sent.get(key);
            if (clause == null) {
                log.add(entry(stage, "JUDGMENT_UNKNOWN_KEY", "judgments", "clauseKey " + TextTruncator.truncate(key, 20)));
                continue;
            }
            if (judged.containsKey(key)) {
                log.add(entry(stage, "JUDGMENT_DUPLICATED", "clause " + key, "앞의 판단만 씀"));
                continue;
            }
            ClauseVerdict verdict = parseVerdict(j.verdict());
            if (verdict == null) {
                log.add(entry(stage, "VERDICT_INVALID", "clause " + key, TextTruncator.truncate(j.verdict(), 20)));
                continue;
            }
            judged.put(key, judge(stage, key, clause, verdict, j, allClauseKeys, clauseNames, log));
        }
        for (String key : sent.keySet()) {
            if (!judged.containsKey(key)) {
                log.add(entry(stage, "JUDGMENT_MISSING", "clause " + key, "판단이 오지 않음"));
            }
        }

        List<NeedMoreRequest> needMore = new ArrayList<>();
        Set<String> needMoreKeys = new LinkedHashSet<>();
        for (JudgeResponse.NeedMore n : nullSafe(response.needMore())) {
            String key = n.clauseKey() == null ? "" : n.clauseKey().trim();
            if (!allowNeedMore) {
                log.add(entry(stage, "NEED_MORE_IGNORED", "clause " + TextTruncator.truncate(key, 20),
                        "추가 조회는 분석당 1회"));
                continue;
            }
            if (!sent.containsKey(key)) {
                log.add(entry(stage, "NEED_MORE_UNKNOWN_KEY", "needMore", "clauseKey " + TextTruncator.truncate(key, 20)));
                continue;
            }
            if (!needMoreKeys.add(key)) {
                continue;
            }
            List<String> keywords = new ArrayList<>();
            for (String k : nullSafe(n.searchKeywords())) {
                String t = k.trim();
                if (!t.isEmpty() && keywords.size() < MAX_KEYWORDS && !keywords.contains(t)) {
                    keywords.add(truncated(stage, t, JudgePrompt.KEYWORD_MAX, "needMore " + key + ".searchKeywords", log));
                }
            }
            String want = n.want() == null || n.want().isBlank() ? null
                    : truncated(stage, n.want().trim(), JudgePrompt.WANT_MAX, "needMore " + key + ".want", log);
            needMore.add(new NeedMoreRequest(key, want, List.copyOf(keywords)));
        }

        List<String> questions = items(stage, response.questionsBeforeSigning(), MAX_QUESTIONS,
                "questionsBeforeSigning", clauseNames, log);
        return new Result(judged, needMore, questions, log);
    }

    private static Judged judge(String stage, String key, SentClause clause, ClauseVerdict verdict,
                                JudgeResponse.Judgment j, Set<String> allClauseKeys, Map<String, String> names,
                                List<Map<String, Object>> log) {
        String target = "clause " + key;
        // 1. 인용: 이 조항에 준 ID 만
        List<String> cited = new ArrayList<>();
        for (String id : nullSafe(j.citedIds())) {
            String t = id.trim();
            if (!clause.labels().contains(t)) {
                log.add(entry(stage, "CITATION_REMOVED", target + ".citedIds", TextTruncator.truncate(t, 20)));
                continue;
            }
            if (!cited.contains(t)) {
                cited.add(t);
            }
        }
        // 2. 규칙 결과 우선, 근거 없는 확인 필요는 낮춤
        if (clause.ruleBased()) {
            if (verdict != ClauseVerdict.NEEDS_CHECK) {
                log.add(entry(stage, "RULE_OVERRIDE", target + ".verdict", verdict + " → NEEDS_CHECK (규칙 결과 우선)"));
            }
            verdict = ClauseVerdict.NEEDS_CHECK;
        } else if (verdict == ClauseVerdict.NEEDS_CHECK && cited.stream().noneMatch(clause.lawLabels()::contains)) {
            log.add(entry(stage, "VERDICT_DOWNGRADED", target + ".verdict", "NEEDS_CHECK → UNCERTAIN (남은 조문 인용 없음)"));
            verdict = ClauseVerdict.UNCERTAIN;
        }
        if (verdict != ClauseVerdict.NEEDS_CHECK && !cited.isEmpty()) {
            // 확인 필요가 아닌 판단은 근거를 화면에 내지 않는다 (cited 로 표시하지 않음)
            cited = List.of();
        }
        // 3. 글: 단정 표현 지우기 → 내부 조항 키 바꾸기 → 길이 (조항 제목에 든 낱말로 AI 문장이 지워지지 않게 이 순서)
        String headline = replaceClauseKeys(stage,
                withoutAssertions(stage, j.headline(), target + ".headline", log), target + ".headline", names, log);
        headline = headline == null ? null
                : truncated(stage, headline, AnalysisClause.HEADLINE_MAX_LENGTH, target + ".headline", log);
        String easy = replaceClauseKeys(stage,
                withoutAssertions(stage, j.easyExplanation(), target + ".easyExplanation", log),
                target + ".easyExplanation", names, log);
        String why = verdict == ClauseVerdict.OK ? null : replaceClauseKeys(stage,
                withoutAssertions(stage, j.whyCheck(), target + ".whyCheck", log), target + ".whyCheck", names, log);
        List<String> ask = items(stage, j.askCounterpart(), MAX_ASK, target + ".askCounterpart", names, log);
        // 4. 관련 조항
        List<String> related = new ArrayList<>();
        for (String r : nullSafe(j.relatedClauses())) {
            String t = r.trim();
            if (t.equals(key) || related.contains(t)) {
                continue;
            }
            if (!allClauseKeys.contains(t)) {
                log.add(entry(stage, "RELATED_UNKNOWN", target + ".relatedClauses", TextTruncator.truncate(t, 20)));
                continue;
            }
            related.add(t);
        }
        return new Judged(key, verdict, headline, easy, why, List.copyOf(cited), List.copyOf(related), ask);
    }

    /** 목록 칸: 단정 표현이 든 항목은 빼고, 항목은 ITEM_MAX 로 자르고, 같은 항목은 하나, max 개까지 */
    private static List<String> items(String stage, List<String> values, int max, String target,
                                      Map<String, String> names, List<Map<String, Object>> log) {
        List<String> out = new ArrayList<>();
        for (String raw : nullSafe(values)) {
            if (raw.isBlank()) {
                continue;
            }
            Matcher m = ASSERTION.matcher(raw);
            if (m.find()) {
                log.add(entry(stage, "ASSERTION_REMOVED", target, "'" + m.group() + "': "
                        + TextTruncator.truncate(raw.trim(), LOG_TEXT_MAX)));
                continue;
            }
            String v = replaceClauseKeys(stage, raw.trim(), target, names, log);
            String t = truncated(stage, v, JudgePrompt.ITEM_MAX, target, log);
            if (out.contains(t)) {
                continue;
            }
            if (out.size() >= max) {
                log.add(entry(stage, "ITEM_DROPPED", target, max + "개 넘음"));
                break;
            }
            out.add(t);
        }
        return List.copyOf(out);
    }

    /**
     * 단정 표현이 든 문장만 지운다. 지운 것이 없으면 원래 글 그대로, 다 지워지면 null.
     * 지운 문장은 log 에 남긴다 (설계: 지운 내용은 validation_log_json 에 기록).
     */
    static String withoutAssertions(String stage, String text, String target, List<Map<String, Object>> log) {
        // 문장 나누기·지우기는 챗봇 검증과 같은 AssertionText 를 쓴다
        return AssertionText.removeSentences(text, (found, sentence) -> log.add(entry(stage, "ASSERTION_REMOVED", target,
                "'" + found + "': " + TextTruncator.truncate(sentence, LOG_TEXT_MAX))));
    }

    /** 화면에 쓸 조항 이름: 조 번호, 없으면 "「제목」 조항", 둘 다 없으면 "관련 조항" */
    static String displayName(String articleNo, String title) {
        if (articleNo != null && !articleNo.isBlank()) {
            return articleNo.trim();
        }
        if (title != null && !title.isBlank()) {
            return "「" + title.trim() + "」 조항";
        }
        return UNKNOWN_CLAUSE_NAME;
    }

    /**
     * 글 속 내부 조항 키(c5)를 조항 이름으로 바꾸고 기록한다 ({@link ClauseKeyText}). 이 분석에 없는 키(c99, "C1 등급")는
     * 원문 그대로 둔다.
     */
    static String replaceClauseKeys(String stage, String text, String target, Map<String, String> names,
                                    List<Map<String, Object>> log) {
        return ClauseKeyText.replace(text, names::get,
                (found, replaced) -> log.add(entry(stage, "CLAUSE_KEY_REPLACED", target, found + " → " + replaced)));
    }

    private static String truncated(String stage, String value, int max, String target, List<Map<String, Object>> log) {
        if (TextTruncator.exceeds(value, max)) {
            log.add(entry(stage, "TOO_LONG", target,
                    value.codePointCount(0, value.length()) + "자 > " + max + "자, 잘라 저장"));
            return TextTruncator.truncate(value, max);
        }
        return value;
    }

    static ClauseVerdict parseVerdict(String value) {
        if (value == null || !JudgePrompt.VERDICTS.contains(value.trim())) {
            return null;
        }
        return ClauseVerdict.valueOf(value.trim());
    }

    static Map<String, Object> entry(String stage, String type, String target, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("type", type);
        m.put("target", target);
        m.put("detail", detail);
        return m;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
