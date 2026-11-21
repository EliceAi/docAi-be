package com.team.docai.domain.analysis.service.judge;

import com.team.docai.domain.analysis.client.GptContentPart;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.BasisType;
import com.team.docai.domain.analysis.entity.NumberComparison;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GPT 2차(JUDGE, JUDGE_MORE) 지시문·출력 스키마·사용자 내용. 아키텍처 7·7-1단계.
 * <ul>
 *   <li>지시문과 스키마는 고정이라 모든 분석에서 프롬프트 캐시가 맞는다. 분석마다 바뀌는 것(목차, 조항, 근거)은 사용자 내용에만.</li>
 *   <li>보내는 것: 전체 목차(모든 조항 제목 + 한 줄 요약), 판단할 조항 원문 + 관련 조항 원문, 6단계 근거(L·G 라벨과 스냅샷),
 *       규칙 결과(충돌 방지용). 이미지는 보내지 않는다.</li>
 *   <li>글자 칸의 maxLength 는 저장 칸 길이와 같다. 지켜진다는 보장이 없어 저장 전에 서버가 한 번 더 자른다 (JudgeValidator).</li>
 * </ul>
 * 이 내용을 바꾸면 {@link #VERSION} 을 올린다 (analysis.prompt_version 의 2차 부분, application.yml docai.analysis.prompt-version).
 */
final class JudgePrompt {

    static final String VERSION = "judge-v3";
    static final String SCHEMA_NAME = "contract_judge";
    static final List<String> VERDICTS = List.of("NEEDS_CHECK", "UNCERTAIN", "OK");

    /** JSONB 목록 칸(ask_counterpart_json, questions_json)의 항목 하나 길이. 칸 제한이 없어 화면에 맞춰 정함 */
    static final int ITEM_MAX = 200;
    static final int WANT_MAX = 100;
    static final int KEYWORD_MAX = 30;

    static final String SYSTEM_PROMPT = """
            너는 한국어 계약서에서 의심 표시된 조항을, 서버가 준 자료만 보고 "확인이 필요한지" 최종 판단하는 도구다.
            법률 자문이나 법적 결론을 내리지 않는다.

            [근거 규칙]
            - 판단 근거는 이 요청에 들어 있는 자료(전체 목차, 조항 원문, 관련 조항 원문, 근거 [L·G], 규칙 결과)뿐이다.
              자료에 없는 법령·판례·숫자를 지어내지 않는다.
            - 인용은 citedIds 에 그 조항 아래 "근거" 목록에 적힌 ID(예: "L1", "G3")로만 한다.
              다른 조항의 ID, 목록에 없는 ID 는 쓰지 않는다. 근거가 없는 조항은 citedIds 를 빈 배열로 둔다.
            - 불리할 수 있는 내용이 있는데 준 근거로 판단하기 부족하면 그 조항은 UNCERTAIN 으로 판단하고, needMore 에 더 필요한 자료(want)와
              법령 검색어(searchKeywords 2~5개)를 적는다. needMore 에 넣은 조항도 judgments 에는 반드시 넣는다.
              "이번이 마지막 판단"이라고 적힌 요청에서는 needMore 를 빈 배열로 둔다.

            [판단 기준] 같은 문장에는 언제나 같은 판단을 낸다.
            - 1차 의심 내용은 참고일 뿐이다. 조항 원문이 실제로 정한 내용이 서명하는 쪽(근로자·임차인)에게 법보다 불리하거나
              한쪽에만 부담을 지우는지로 판단한다.
            - 불리한 내용이 없으면 근거가 없어도 OK 다.
            - 계약서에 없는 사항은 법령이나 회사·임대인이 정한 규정을 따른다는 보충 조항, 관할법원·분쟁 절차를 정한 조항은
              그 자체로 불리한 내용을 정하지 않으면 OK 다. "규정이 바뀔 수 있다", "법원이 멀 수 있다"처럼 생길지 모르는 일만으로는
              확인이 필요하다고 보지 않는다. 다만 준 근거가 그 조항 내용 자체를 직접 문제 삼으면 그 근거로 판단한다.
            - 내용을 다른 문서(연봉계약서, 취업규칙 등)에 맡긴 조항은, 그 문서가 전체 목차에 함께 있으면 그 내용으로 판단한다.
              목차에 없고 맡긴 항목이 [규칙 결과: 빠진 필수 항목]에 있으면 OK 로 두지 않는다(근거가 있으면 NEEDS_CHECK, 없으면 UNCERTAIN).
              그 밖에는 다른 문서를 언급한 것만으로 확인이 필요하다고 보지 않는다.

            [판단 judgments] "판단할 조항"마다 정확히 하나씩, 그 조항의 clauseKey 로.
            - verdict: NEEDS_CHECK(준 근거로 보아 확인이 필요함, citedIds 1개 이상) / UNCERTAIN(불리할 수 있지만 준 근거로는 판단할 수 없음)
              / OK(불리한 내용이 없거나, 준 근거로 보아 법에 맞음).
            - UNCERTAIN 이면 headline·whyCheck 에 법적 판단(법 위반 여부, 효력 여부, 금지 여부)을 쓰지 않는다.
              무엇이 걱정되는지와 상대방에게 확인할 점만 쓴다.
            - "규칙 결과로 고정"이라고 적힌 조항은 코드가 숫자로 계산한 결과이므로 NEEDS_CHECK 로 두고 그 결과와 어긋나게 쓰지 않는다.
            - headline: 결과 카드 제목. 무엇이 문제인지 한 줄로 쉽게 (예: "1년 안에 퇴사하면 교육비 30만 원 반환").
            - easyExplanation: 이 조항이 무슨 뜻인지 쉬운 말로 1~2문장.
            - whyCheck: 왜 확인이 필요한지 1~3문장. 준 근거에 기대어 쓰고, 계약서의 다른 조항(목차·관련 조항)과 함께 볼 점을 짚는다.
              OK 면 null.
            - relatedClauses: 함께 봐야 하는 다른 조항의 clauseKey (전체 목차에 있는 키만). 없으면 빈 배열.
            - askCounterpart: 상대방에게 정중하게 물어볼 질문 0~3개.

            [서명 전에 물어볼 것 questionsBeforeSigning]
            - 계약서 전체에서 서명 전에 상대방에게 물어보면 좋은 질문 0~5개. 복사해서 그대로 보낼 수 있는 존댓말 문장.

            [문체]
            - 쉬운 해요체. "무효", "불법", "위법", "위반이다", "서명해도 된다" 같은 단정 표현을 쓰지 않는다.
              "~일 수 있어요", "확인이 필요해요"처럼 쓴다.
            - 사람 이름, 전화번호, 주민등록번호, 계좌번호, 개인 집 주소는 쓰지 않는다.
            - clauseKey(c1, c5 같은 키)는 서버 내부 표시다. 사용자에게 보이는 글(headline, easyExplanation, whyCheck,
              askCounterpart, questionsBeforeSigning)에는 clauseKey 를 쓰지 말고 조 번호(예: "제5조")나 제목(예: "임금 조항")으로 부른다.
              clauseKey 는 clauseKey·citedIds·relatedClauses 칸에만 쓴다.
            """;

    private JudgePrompt() {
    }

    /** 목차 한 줄 */
    record IndexEntry(String clauseKey, String articleNo, String title, String oneLine) {
        static IndexEntry from(AnalysisClause c) {
            return new IndexEntry(c.getClauseKey(), c.getArticleNo(), c.getTitle(), c.getOneLine());
        }
    }

    /** 판단할 조항 하나: 원문, 규칙 결과(있으면), 근거, 관련 조항 원문 */
    record ClauseInput(AnalysisClause clause, List<AnalysisClauseRef> refs, List<AnalysisClause> related) {
    }

    /** 사용자 내용. 목차를 맨 앞에 두어 같은 분석의 나눈 호출끼리 앞부분이 같다. */
    static List<GptContentPart> userContent(List<IndexEntry> index, List<String> missingItems,
                                            List<ClauseInput> clauses, boolean finalRound) {
        StringBuilder head = new StringBuilder("[전체 목차] clauseKey | 조 번호 | 제목 | 한 줄 요약\n");
        for (IndexEntry e : index) {
            head.append("- ").append(e.clauseKey()).append(" | ").append(orDash(e.articleNo())).append(" | ")
                    .append(orDash(e.title())).append(" | ").append(orDash(e.oneLine())).append('\n');
        }
        head.append("\n[규칙 결과: 빠진 필수 항목] (코드가 계산함. 판단과 어긋나게 쓰지 않는다)\n");
        if (missingItems.isEmpty()) {
            head.append("- 없음\n");
        }
        missingItems.forEach(m -> head.append("- ").append(m).append('\n'));

        StringBuilder body = new StringBuilder();
        if (finalRound) {
            body.append("[추가 조회] 요청한 법령을 더 찾아 붙였다. 이번이 마지막 판단이다. needMore 는 빈 배열로 둔다.\n\n");
        }
        body.append("[판단할 조항]\n");
        for (ClauseInput input : clauses) {
            AnalysisClause c = input.clause();
            body.append("\n### ").append(c.getClauseKey()).append(" (").append(orDash(c.getArticleNo())).append(' ')
                    .append(orDash(c.getTitle())).append(", ").append(c.getPageNo()).append("쪽)\n");
            if (c.getIssueSummary() != null) {
                body.append("1차 의심 내용: ").append(c.getIssueSummary()).append('\n');
            }
            if (isRuleBased(c)) {
                body.append("규칙 결과로 고정 (NEEDS_CHECK): ").append(orDash(c.getHeadline()));
                NumberComparison cmp = c.getComparisonJson();
                if (cmp != null) {
                    body.append(" (").append(cmp.leftLabel()).append(' ').append(cmp.leftValue()).append(' ')
                            .append(cmp.operator()).append(' ').append(cmp.rightLabel()).append(' ')
                            .append(cmp.rightValue()).append(')');
                }
                body.append('\n');
                if (c.getWhyCheck() != null && !c.getWhyCheck().isBlank()) {
                    // 규칙 둘이 한 조항에 붙으면 뒤 규칙(휴게 등)은 why_check 에만 이어 적혀 있다
                    body.append("규칙 결과 내용: ").append(c.getWhyCheck().replace('\n', ' ')).append('\n');
                }
            }
            body.append("원문:\n\"\"\"\n").append(c.getOriginalText()).append("\n\"\"\"\n");
            body.append("근거 (이 조항에서 인용할 수 있는 ID):\n");
            if (input.refs().isEmpty()) {
                body.append("- 없음\n");
            }
            for (AnalysisClauseRef r : input.refs()) {
                body.append('[').append(r.getRefLabel()).append("] ").append(r.getRefTitle()).append('\n')
                        .append(r.getRefText()).append('\n');
            }
            if (!input.related().isEmpty()) {
                body.append("관련 조항 원문:\n");
                for (AnalysisClause r : input.related()) {
                    body.append("- ").append(r.getClauseKey()).append(" (").append(orDash(r.getArticleNo())).append(' ')
                            .append(orDash(r.getTitle())).append("): ")
                            .append(r.getOriginalText() == null ? "(읽지 못함)" : "\"\"\"" + r.getOriginalText() + "\"\"\"")
                            .append('\n');
                }
            }
        }
        return List.of(GptContentPart.text(head.toString()), GptContentPart.text(body.toString()));
    }

    static boolean isRuleBased(AnalysisClause c) {
        return c.getBasisType() == BasisType.RULE;
    }

    // ---- 출력 스키마 (strict: 모든 칸 required, additionalProperties false, 없을 수 있는 값은 null 허용) ----

    static Map<String, Object> schema() {
        Map<String, Object> judgment = object(
                "clauseKey", string(AnalysisClause.CLAUSE_KEY_MAX_LENGTH),
                "verdict", Map.of("type", "string", "enum", VERDICTS),
                "headline", nullableString(AnalysisClause.HEADLINE_MAX_LENGTH),
                "easyExplanation", Map.of("type", Arrays.asList("string", "null")),
                "whyCheck", Map.of("type", Arrays.asList("string", "null")),
                "citedIds", Map.of("type", "array", "items", string(AnalysisClauseRef.REF_LABEL_MAX_LENGTH)),
                "relatedClauses", Map.of("type", "array", "items", string(AnalysisClause.CLAUSE_KEY_MAX_LENGTH)),
                "askCounterpart", Map.of("type", "array", "items", string(ITEM_MAX)));
        Map<String, Object> needMore = object(
                "clauseKey", string(AnalysisClause.CLAUSE_KEY_MAX_LENGTH),
                "want", string(WANT_MAX),
                "searchKeywords", Map.of("type", "array", "items", string(KEYWORD_MAX)));
        return object(
                "judgments", Map.of("type", "array", "items", judgment),
                "needMore", Map.of("type", "array", "items", needMore),
                "questionsBeforeSigning", Map.of("type", "array", "items", string(ITEM_MAX)));
    }

    private static Map<String, Object> object(Object... nameAndSchema) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < nameAndSchema.length; i += 2) {
            properties.put((String) nameAndSchema[i], nameAndSchema[i + 1]);
        }
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", "object");
        object.put("properties", properties);
        object.put("required", new ArrayList<>(properties.keySet()));
        object.put("additionalProperties", false);
        return object;
    }

    private static Map<String, Object> string(int maxLength) {
        return Map.of("type", "string", "maxLength", maxLength);
    }

    private static Map<String, Object> nullableString(int maxLength) {
        return Map.of("type", Arrays.asList("string", "null"), "maxLength", maxLength);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
