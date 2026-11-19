package com.team.docai.domain.chat.service;

import com.team.docai.domain.analysis.service.AssertionText;
import com.team.docai.domain.analysis.service.ClauseKeyText;
import com.team.docai.domain.chat.dto.ChatGptResponse;
import com.team.docai.domain.chat.entity.ActionType;
import com.team.docai.domain.chat.entity.AnswerType;
import com.team.docai.domain.chat.entity.ChatAction;
import com.team.docai.domain.chat.entity.ChatPartial;
import com.team.docai.domain.chat.entity.ChatReference;
import com.team.docai.domain.chat.entity.ReferenceType;
import com.team.docai.global.util.TextTruncator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 챗봇 답변 검증 (분석 8단계 검증과 같은 원리). DB·GPT 를 쓰지 않는 순수 계산.
 * <ol>
 *   <li>citedIds 는 서버가 준 법령 자료 ID 만 남긴다. 남은 것만 참고한 정보(LAW_DB)에 서버가 붙인 이름으로 넣는다.</li>
 *   <li>답변 글에 준 적 없는 법 조문 이름("○○법 제○조")이 나오면 그 문장을 지운다. 준 자료에 있는 조문을 글에서 말했으면
 *       인용으로 센다.</li>
 *   <li>법적 판단을 썼다는데(mentionsLaw) 남은 조문 인용이 없거나, 위에서 문장을 지웠으면 ANSWERED → PARTIAL
 *       (근거가 부족하면 확인한 것 / 알 수 없는 것으로 나눠 답함, 아키텍처 챗봇 3단계).</li>
 *   <li>단정 표현("무효", "불법", "서명해도 된다" 등, {@link AssertionText})이 든 문장·항목은 지운다.</li>
 *   <li>내부 키·표현을 감춘다: 이 분석의 보이는 조항 키(c5) → 조 번호, 뒤 조사도 받침에 맞춤 ({@link ClauseKeyText}).
 *       이번에 준 근거 번호는 괄호 안([L1], (L1, G2))이면 지우고, 괄호 없이 쓴 것(L1, "L1, G2")은 뒤가 글 끝·줄 끝·문장부호·쉼표일
 *       때만 지운다. 조사나 다른 글자가 바로 붙거나 낱말이 이어지면(L1과, G1등급, L1-2, L1 기준) 원문 그대로 둔다.
 *       "제공된 근거"·"규칙 결과"·"서버가 준" 같은 말은 사용자 말로 바꾸고, "프롬프트"·"지시문"·"시스템 메시지" 같은
 *       AI 내부 사정 문장은 지운다. 강조 태그는 &lt;b&gt; 만 남긴다.</li>
 *   <li>남은 조문 인용이 없는데 답변에 법적 판단({@link #hasLegalJudgment})이 있으면 mentionsLaw 와 상관없이 PARTIAL 로 낮춘다.</li>
 *   <li>완료된 분석이 없으면 partial.confirmed(계약서로 확인한 것)를 비운다.</li>
 *   <li>참고한 정보: MY_DOCUMENT 는 완료 분석을 넘겼을 때만, CURRENT_SCREEN·APP_GUIDE 는 AI 가 썼다고 할 때.
 *       OUT_OF_SCOPE 면 APP_GUIDE 만.</li>
 *   <li>이동 버튼은 서버가 만든다: 조항은 이 분석의 보이는 조항만, 화면은 정한 목록만, 필요한 ID 가 없으면 버린다.
 *       이름(label)도 서버가 정한다 (AI 가 쓴 글을 버튼에 넣지 않음). 문서 전송·일정 저장 같은 동작 버튼은 없다.</li>
 * </ol>
 * 지운 것·바꾼 것은 log 에 남긴다 (대화에는 검증 기록 칸이 없어 서버 로그에만, 내용 없이 종류만).
 */
final class ChatAnswerValidator {

    static final int MAX_ACTIONS = 3;
    static final int MAX_PARTIAL_ITEMS = 5;

    static final String FALLBACK_ANSWER =
            "지금 확인한 내용만으로는 단정해서 답하기 어려워요. 계약서 내용과 관련 법 조문을 함께 확인해 보세요.";
    static final String NO_LAW_UNKNOWN = "관련 법 조문을 찾지 못해 법적으로 어떤지는 확인할 수 없어요.";
    static final String DEFAULT_UNKNOWN = "지금 가진 정보만으로는 확인할 수 없는 부분이 있어요.";
    static final String MY_DOCUMENT_DEFAULT_LABEL = "내 계약서";
    static final String APP_GUIDE_LABEL = "앱 사용법";

    /** 화면 이동 버튼: 화면 → 이름. S08·S13 은 완료 분석, S12 는 비교가 있어야 한다 */
    static final Map<String, String> SCREEN_ACTION_LABELS = Map.of(
            "S01", "홈으로",
            "S08", "분석 결과 보기",
            "S12", "비교 결과 보기",
            "S13", "일정 등록 화면으로",
            "S14", "내 일정 보기",
            "S15", "내 계약서 보기");
    static final String DOCUMENT_TYPE_ACTION_LABEL = "문서 종류 고르기";

    /** 법 조문 이름 (근로기준법 제20조, 주택임대차보호법 시행령 제2조의2). 앞 낱말은 법·령·규칙으로 끝남 */
    static final Pattern LAW_MENTION = Pattern.compile(
            "([가-힣A-Za-z·]+(?:\\s*시행령|\\s*시행규칙)?)\\s*(제\\s*\\d+\\s*조(?:\\s*의\\s*\\d+)?)");
    /** 법령 이름 끝 (취업규칙·대통령령처럼 조문 DB 에 없는 규정을 법으로 보지 않게 좁게) */
    private static final Pattern LAW_NAME_END = Pattern.compile(".*(?:법|법률|시행령|시행규칙)$");
    private static final Pattern CLAUSE_KEY = Pattern.compile("^[cC]0*(\\d{1,6})$");
    private static final Pattern TAG = Pattern.compile("<(?!/?b>)[^>]*>");
    /**
     * 근거 번호 묶음: 괄호 안([L1], (L1, G2)) 또는 괄호 없이(L1, G2). 이번에 준 ID 만 지운다.
     * 괄호 없는 것은 묶음 전체(원자 그룹이라 더 짧게 끊지 않음) 뒤가 글 끝·줄 끝·문장부호·쉼표일 때만
     * (L1과, G1등급, L1-2, L1 기준은 둠).
     */
    private static final Pattern BRACKET_ID = Pattern.compile(
            "\\s*[\\[(]\\s*([LG]\\d{1,3}(?:\\s*[,，]\\s*[LG]\\d{1,3})*)\\s*[\\])]");
    private static final Pattern BARE_ID = Pattern.compile(
            "[^\\S\\n]*(?<![A-Za-z0-9_])((?>[LG]\\d{1,3}(?:\\s*[,，]\\s*[LG]\\d{1,3})*))(?![A-Za-z0-9_\\-])"
                    + "(?=[^\\S\\n]*(?:[.!?,，…\\n]|$))");
    private static final Pattern ID_SPLIT = Pattern.compile("\\s*[,，]\\s*");
    /**
     * AI 내부 사정을 말하는 문장 (지시문에서 금지한 말). 바꿀 말이 없어 그 문장을 지운다.
     * AI 내부에서만 쓰는 말로 좁힌다: "시스템"·"지시"·"내부 규칙"·"주어진 지시"는 계약 내용(근태 시스템, 업무 지시,
     * 회사 내부 규칙)에도 나오므로 넣지 않는다.
     */
    static final Pattern INTERNAL_SENTENCE = Pattern.compile("프롬프트|지시문|시스템\\s*(?:메시지|지시|프롬프트)");
    /** 늘 법적 판단인 말 ("위법"·"불법" 은 단정 표현이라 보통 먼저 지워짐) */
    static final Pattern STRONG_LEGAL = Pattern.compile(
            "법적(?!\\s*(?:도움|절차|상담|지원|조언|안내|자문|구제|대응))|위법|법률상(?!담)|(?<![가-힣])법상");
    /** 법 낱말과 같은 문장에 있을 때만 법적 판단이 되는 말 ("일정이 어긋나지 않게", "계약을 위반하면" 은 판단 아님) */
    static final Pattern LEGAL_VERB = Pattern.compile("어긋|위반|금지|허용|보장|따르면|따라|정하|정한|정해|맞지\\s*않");
    /** 낱말 끝 조사 (긴 것부터). 더 떼어지지 않을 때까지 반복해서 뗀다 (에서도 → 에서 + 도, 과도 → 과 + 도) */
    private static final java.util.List<String> TOKEN_JOSA = java.util.List.of(
            "에서는", "에서도", "으로는", "으로도", "에게는", "에게도",
            "에서", "에는", "에도", "으로", "에게", "로는", "로도", "과도", "와도",
            "에", "이", "가", "을", "를", "은", "는", "의", "과", "와", "도", "만", "로");
    /** "법" 으로 끝나지만 법령이 아닌 낱말 */
    private static final java.util.List<String> NOT_LAW_WORDS = java.util.List.of(
            "방법", "용법", "문법", "사용법", "계산법", "작성법", "해결법", "요법", "기법", "수법", "편법", "화법", "어법", "필법",
            "촬영법", "대처법", "신청법", "처리법", "요리법", "이용법");
    static final String UNKNOWN_CLAUSE_NAME = "관련 조항";
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** 내부 표현 → 사용자 말 (긴 것부터. 뒤에 붙는 조사가 어색해지지 않게 끝 글자 받침이 같은 말로) */
    private static final Map<String, String> INTERNAL_PHRASES = orderedMap(
            "서버가 준 자료", "확인한 자료",
            "서버가 제공한", "확인한",
            "서버에서 준", "확인한",
            "서버가 준", "확인한",
            "제공된 법령 자료", "관련 법령 정보",
            "제공된 근거", "관련 근거",
            "제공된 자료", "확인한 자료",
            "법령 자료", "법령 정보",
            "규칙 결과", "계산 결과");

    record Result(AnswerType answerType, String answer, List<ChatReference> references, ChatPartial partial,
                  List<ChatAction> actions, String summary, List<String> log) {
    }

    private ChatAnswerValidator() {
    }

    static Result validate(ChatGptResponse response, ChatContext ctx) {
        List<String> log = new ArrayList<>();
        Map<String, ChatContext.Source> sources = new LinkedHashMap<>();
        ctx.sources().forEach(s -> sources.put(s.id(), s));
        Map<String, String> clauseNames = ctx.clauseNames();

        AnswerType type = parseType(response.answerType());

        // 1. 인용: 준 ID 만
        Set<String> cited = new LinkedHashSet<>();
        for (String id : nullSafe(response.citedIds())) {
            String t = id.trim();
            if (sources.containsKey(t)) {
                cited.add(t);
            } else {
                log.add("CITATION_REMOVED");
            }
        }
        Map<String, ChatContext.Source> byLawName = new LinkedHashMap<>();
        sources.values().forEach(s -> byLawName.putIfAbsent(normalizeLaw(s.referenceLabel()), s));

        // 2. 답변 글
        boolean[] unknownLawRemoved = {false};
        String answer = clean(response.answer(), clauseNames, byLawName, sources.keySet(), cited, unknownLawRemoved, log);

        // 3. 근거 부족 → PARTIAL
        boolean noLawCitation = cited.isEmpty();
        boolean legalWords = answer != null && hasLegalJudgment(answer);
        if (noLawCitation && legalWords && !response.mentionsLaw()) {
            log.add("LEGAL_WORDS_WITHOUT_CITATION");
        }
        boolean lawProblem = (noLawCitation && (response.mentionsLaw() || legalWords)) || unknownLawRemoved[0];
        List<String> extraUnknown = new ArrayList<>();
        if (type != AnswerType.OUT_OF_SCOPE && lawProblem) {
            if (type == AnswerType.ANSWERED) {
                log.add("DOWNGRADED_TO_PARTIAL");
                type = AnswerType.PARTIAL;
            }
            extraUnknown.add(NO_LAW_UNKNOWN);
        }
        if (answer == null) {
            log.add("ANSWER_REPLACED");
            answer = FALLBACK_ANSWER;
            if (type == AnswerType.ANSWERED) {
                type = AnswerType.PARTIAL;
            }
        }

        // 4. 일부만 답함
        ChatPartial partial = null;
        if (type == AnswerType.PARTIAL) {
            ChatGptResponse.Partial p = response.partial();
            List<String> confirmed = items(p == null ? null : p.confirmed(), clauseNames, byLawName, sources.keySet(), log);
            if (ctx.analysis() == null && !confirmed.isEmpty()) {
                // 완료된 분석이 없으면 "계약서로 확인한 것" 이 있을 수 없다
                log.add("CONFIRMED_DROPPED_NO_ANALYSIS");
                confirmed = List.of();
            }
            List<String> unknown = new ArrayList<>(items(p == null ? null : p.unknown(), clauseNames, byLawName, sources.keySet(), log));
            for (String u : extraUnknown) {
                if (!unknown.contains(u) && unknown.size() < MAX_PARTIAL_ITEMS) {
                    unknown.add(u);
                }
            }
            if (unknown.isEmpty()) {
                unknown.add(DEFAULT_UNKNOWN);
            }
            partial = new ChatPartial(confirmed, unknown);
        }

        // 5. 참고한 정보
        Set<String> used = new LinkedHashSet<>();
        nullSafe(response.usedSources()).forEach(s -> used.add(s.trim()));
        List<ChatReference> references = new ArrayList<>();
        if (type == AnswerType.OUT_OF_SCOPE) {
            if (!cited.isEmpty()) {
                log.add("OUT_OF_SCOPE_CITATIONS_DROPPED");
            }
            if (used.contains("APP_GUIDE")) {
                references.add(new ChatReference(ReferenceType.APP_GUIDE, APP_GUIDE_LABEL));
            }
        } else {
            if (used.contains("CURRENT_SCREEN")) {
                references.add(new ChatReference(ReferenceType.CURRENT_SCREEN, currentScreenLabel(ctx)));
            }
            if (used.contains("MY_DOCUMENT")) {
                if (ctx.analysis() != null) {
                    String title = ctx.linkedDocument() == null ? null : ctx.linkedDocument().title();
                    references.add(new ChatReference(ReferenceType.MY_DOCUMENT,
                            title == null || title.isBlank() ? MY_DOCUMENT_DEFAULT_LABEL : title));
                } else {
                    log.add("MY_DOCUMENT_DROPPED");
                }
            }
            Set<String> lawLabels = new LinkedHashSet<>();
            cited.forEach(id -> lawLabels.add(sources.get(id).referenceLabel()));
            lawLabels.forEach(l -> references.add(new ChatReference(ReferenceType.LAW_DB, l)));
            if (used.contains("APP_GUIDE")) {
                references.add(new ChatReference(ReferenceType.APP_GUIDE, APP_GUIDE_LABEL));
            }
        }

        // 6. 이동 버튼
        List<ChatAction> actions = actions(response.actions(), type, ctx, clauseNames, log);

        String summary = response.conversationSummary() == null || response.conversationSummary().isBlank() ? null
                : TextTruncator.truncate(response.conversationSummary().trim(), ChatPrompt.SUMMARY_MAX);

        return new Result(type, TextTruncator.truncate(answer, ChatPrompt.ANSWER_MAX), List.copyOf(references), partial,
                actions, summary, List.copyOf(log));
    }

    /**
     * 사용자에게 보이는 글 한 칸: 태그·ID 지우기 → 내부 표현 바꾸기 → 단정 표현 문장 지우기 → 준 적 없는 법 조문 문장 지우기
     * → 조항 키 바꾸기. 다 지워지면 null.
     */
    private static String clean(String text, Map<String, String> clauseNames, Map<String, ChatContext.Source> byLawName,
                                Set<String> sourceIds, Set<String> cited, boolean[] unknownLawRemoved, List<String> log) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = hideIdsAndPhrases(text, sourceIds, log);
        t = AssertionText.removeSentences(t, (found, sentence) -> log.add("ASSERTION_REMOVED"));
        if (t == null) {
            return null;
        }
        t = removeSentences(t, s -> hasUnknownLaw(s, byLawName, cited), () -> {
            unknownLawRemoved[0] = true;
            log.add("UNKNOWN_LAW_REMOVED");
        });
        if (t == null) {
            return null;
        }
        t = removeSentences(t, s -> INTERNAL_SENTENCE.matcher(s).find(), () -> log.add("INTERNAL_SENTENCE_REMOVED"));
        if (t == null) {
            return null;
        }
        t = replaceClauseKeys(t, clauseNames, log);
        return t.isBlank() ? null : t.trim();
    }

    /** 태그(&lt;b&gt; 빼고)·이번에 준 근거 번호([L1], (L1, G2), L1)를 지우고 내부 표현을 사용자 말로 바꾼다 */
    private static String hideIdsAndPhrases(String text, Set<String> sourceIds, List<String> log) {
        String t = TAG.matcher(text).replaceAll("");
        t = removeGivenIds(t, BRACKET_ID, sourceIds, log);
        t = removeGivenIds(t, BARE_ID, sourceIds, log);
        for (Map.Entry<String, String> e : INTERNAL_PHRASES.entrySet()) {
            if (t.contains(e.getKey())) {
                log.add("INTERNAL_PHRASE_REPLACED");
                t = t.replace(e.getKey(), e.getValue());
            }
        }
        return t;
    }

    /** 묶음 안의 ID 가 모두 이번에 준 근거 ID 일 때만 지운다. 하나라도 아니면(C4 비자, L1 기준) 원문 그대로 */
    private static String removeGivenIds(String text, Pattern pattern, Set<String> sourceIds, List<String> log) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            boolean allGiven = ID_SPLIT.splitAsStream(m.group(1).trim()).allMatch(sourceIds::contains);
            m.appendReplacement(sb, allGiven ? "" : Matcher.quoteReplacement(m.group()));
            changed |= allGiven;
        }
        if (!changed) {
            return text;
        }
        m.appendTail(sb);
        log.add("SOURCE_ID_REMOVED");
        return sb.toString();
    }

    /**
     * 조항 키를 조 번호로 ({@link ClauseKeyText}, 조사도 받침에 맞춤). 이 분석의 보이는 조항 키만 바꾸고, 그 밖의 것
     * (c99, "C1 등급")은 원문 그대로 둔다 (ClauseKeyText·JudgeValidator 와 같은 원칙).
     */
    private static String replaceClauseKeys(String text, Map<String, String> clauseNames, List<String> log) {
        return ClauseKeyText.replace(text, clauseNames::get, (found, replaced) -> log.add("CLAUSE_KEY_REPLACED"));
    }

    /**
     * 근거 조문이 필요한 법적 판단이 있는지 (문장마다).
     * ① "법적", "위법", "법률상" 은 늘 판단 ② "어긋", "위반", "따르면" 같은 말은 같은 문장에 법 낱말(…법, 법령, 법률, 조문)이
     * 있을 때만. 법 낱말에서 방법·사용법·계산법처럼 법령이 아닌 낱말은 뺀다. "법률 상담을 받아 보세요" 는 ②의 말이 없어 판단 아님.
     */
    static boolean hasLegalJudgment(String text) {
        for (AssertionText.Sentence sentence : AssertionText.split(text)) {
            String s = sentence.text();
            if (STRONG_LEGAL.matcher(s).find()) {
                return true;
            }
            boolean verb = LEGAL_VERB.matcher(s).find();
            for (String raw : SPACES.split(s)) {
                String word = stripJosa(raw.replaceAll("[^가-힣]", ""));
                // "근로기준법상", "민법상", "노동법상": 법 낱말 + 상 은 늘 판단
                if (word.endsWith("법상") && isLawWord(word.substring(0, word.length() - 1))) {
                    return true;
                }
                if (verb && isLawWord(stripSuffix(word))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 끝 조사를 더 떼어지지 않을 때까지 뗀다 */
    private static String stripJosa(String word) {
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String j : TOKEN_JOSA) {
                if (word.length() > j.length() && word.endsWith(j)) {
                    word = word.substring(0, word.length() - j.length());
                    stripped = true;
                    break;
                }
            }
        }
        return word;
    }

    /** "법상", "법적" 의 상·적을 뗀다 */
    private static String stripSuffix(String word) {
        if (word.length() > 1 && (word.endsWith("상") || word.endsWith("적"))) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    /** 법 낱말: …법(방법·사용법처럼 법령이 아닌 낱말 빼고), 법령, 법률, 조문 */
    private static boolean isLawWord(String word) {
        if (word.endsWith("조문") || word.endsWith("법령") || word.endsWith("법률")) {
            return true;
        }
        return word.endsWith("법") && NOT_LAW_WORDS.stream().noneMatch(word::endsWith);
    }

    /** 목록 칸 (partial): 단정 표현·준 적 없는 법 조문이 든 항목은 빼고, 키를 바꾸고, ITEM_MAX 로 자르고, 5개까지 */
    private static List<String> items(List<String> values, Map<String, String> clauseNames,
                                      Map<String, ChatContext.Source> byLawName, Set<String> sourceIds, List<String> log) {
        List<String> out = new ArrayList<>();
        for (String raw : nullSafe(values)) {
            if (raw.isBlank()) {
                continue;
            }
            if (AssertionText.contains(raw)) {
                log.add("ASSERTION_REMOVED");
                continue;
            }
            if (hasUnknownLaw(raw, byLawName, new LinkedHashSet<>())) {
                log.add("UNKNOWN_LAW_REMOVED");
                continue;
            }
            if (INTERNAL_SENTENCE.matcher(raw).find()) {
                log.add("INTERNAL_SENTENCE_REMOVED");
                continue;
            }
            String t = hideIdsAndPhrases(raw, sourceIds, log);
            t = replaceClauseKeys(t.trim(), clauseNames, log);
            t = TextTruncator.truncate(t, ChatPrompt.ITEM_MAX);
            if (t.isBlank() || out.contains(t)) {
                continue;
            }
            if (out.size() >= MAX_PARTIAL_ITEMS) {
                log.add("ITEM_DROPPED");
                break;
            }
            out.add(t);
        }
        return out;
    }

    /**
     * 글에 법 조문 이름이 있는데 준 자료에 없는 조문이면 true. 준 자료의 조문이면 인용(cited)에 더한다.
     */
    static boolean hasUnknownLaw(String text, Map<String, ChatContext.Source> byLawName, Set<String> cited) {
        Matcher m = LAW_MENTION.matcher(text);
        boolean unknown = false;
        while (m.find()) {
            String name = SPACES.matcher(m.group(1)).replaceAll("");
            if (!LAW_NAME_END.matcher(name).matches()) {
                continue;
            }
            ChatContext.Source source = byLawName.get(normalizeLaw(name + m.group(2)));
            if (source == null) {
                unknown = true;
            } else {
                cited.add(source.id());
            }
        }
        return unknown;
    }

    private static List<ChatAction> actions(List<ChatGptResponse.Action> candidates, AnswerType type, ChatContext ctx,
                                            Map<String, String> clauseNames, List<String> log) {
        Map<String, Long> clauseIds = ctx.clauseIds();
        Long analysisId = ctx.analysis() == null ? null : ctx.analysis().analysisId();
        List<ChatAction> out = new ArrayList<>();
        for (ChatGptResponse.Action a : nullSafe(candidates)) {
            ActionType actionType = parseAction(a.type());
            String target = a.target() == null ? "" : a.target().trim();
            ChatAction action = null;
            if (actionType == ActionType.OPEN_CLAUSE && type != AnswerType.OUT_OF_SCOPE) {
                String key = normalizeClauseKey(target);
                Long clauseId = key == null ? null : clauseIds.get(key);
                if (clauseId != null) {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("analysisId", analysisId);
                    params.put("clauseId", clauseId);
                    action = new ChatAction(ActionType.OPEN_CLAUSE, "근거 조항 보기 (" + clauseNames.get(key) + ")", params);
                }
            } else if (actionType == ActionType.OPEN_SCREEN) {
                action = screenAction(target, analysisId, ctx.comparisonId());
            } else if (actionType == ActionType.OPEN_DOCUMENT_TYPE) {
                action = new ChatAction(ActionType.OPEN_DOCUMENT_TYPE, DOCUMENT_TYPE_ACTION_LABEL, Map.of());
            }
            if (action == null) {
                log.add("ACTION_DROPPED");
                continue;
            }
            ChatAction candidate = action;
            if (out.stream().anyMatch(existing -> existing.type() == candidate.type()
                    && existing.params().equals(candidate.params()))) {
                continue;
            }
            if (out.size() >= MAX_ACTIONS) {
                log.add("ACTION_DROPPED");
                break;
            }
            out.add(action);
        }
        return List.copyOf(out);
    }

    private static ChatAction screenAction(String screen, Long analysisId, Long comparisonId) {
        String label = SCREEN_ACTION_LABELS.get(screen);
        if (label == null) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("screen", screen);
        switch (screen) {
            case "S08", "S13" -> {
                if (analysisId == null) {
                    return null;
                }
                params.put("analysisId", analysisId);
            }
            case "S12" -> {
                if (comparisonId == null) {
                    return null;
                }
                params.put("comparisonId", comparisonId);
            }
            default -> {
                // 홈, 내 일정, 내 계약서: 화면 번호만
            }
        }
        return new ChatAction(ActionType.OPEN_SCREEN, label, params);
    }

    private static String currentScreenLabel(ChatContext ctx) {
        if (ctx.focusClause() != null) {
            return ChatContext.displayName(ctx.focusClause().articleNo(), ctx.focusClause().title());
        }
        if (ctx.comparison() != null && "S12".equals(ctx.screen())) {
            return "비교 결과";
        }
        String name = ChatPrompt.SCREEN_NAMES.get(ctx.screen());
        return name == null ? "현재 화면" : name + " 화면";
    }

    /** 문장 지우기 (AssertionText 와 같은 문장 나누기·잇기). 지운 것이 없으면 원래 글, 다 지워지면 null */
    private static String removeSentences(String text, Predicate<String> drop, Runnable onDrop) {
        boolean[] dropped = {false};
        String out = AssertionText.removeSentences(text, (Predicate<String>) s -> {
            if (drop.test(s)) {
                onDrop.run();
                dropped[0] = true;
                return true;
            }
            return false;
        });
        return dropped[0] ? out : text;
    }

    /** "c05", " C5 " → "c5". 키 모양이 아니면 null */
    private static String normalizeClauseKey(String target) {
        Matcher m = CLAUSE_KEY.matcher(target);
        return m.matches() ? "c" + Integer.parseInt(m.group(1)) : null;
    }

    /** 띄어쓰기 없애고 비교 ("근로기준법 제 20 조" = "근로기준법제20조") */
    static String normalizeLaw(String label) {
        return label == null ? "" : SPACES.matcher(label).replaceAll("");
    }

    private static AnswerType parseType(String value) {
        if (value == null) {
            return AnswerType.ANSWERED;
        }
        try {
            return AnswerType.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return AnswerType.PARTIAL;
        }
    }

    private static ActionType parseAction(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ActionType.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, String> orderedMap(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
