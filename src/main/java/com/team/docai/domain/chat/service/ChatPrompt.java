package com.team.docai.domain.chat.service;

import com.team.docai.domain.analysis.dto.AnalysisChatInfo;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.chat.entity.ChatRole;
import com.team.docai.domain.comparison.dto.ComparisonForChatInfo.Change;
import com.team.docai.domain.analysis.client.GptContentPart;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.global.util.TextTruncator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GPT 챗봇(CHAT) 지시문·출력 스키마·사용자 내용 (아키텍처 "AI 질문(챗봇)도 같은 원리").
 * <ul>
 *   <li>이미지는 보내지 않는다 (가리기 전 원본 이미지는 챗봇에 보내지 않음, 명세 비고). 분석이 저장한 글만.</li>
 *   <li>지시문과 스키마는 고정이라 프롬프트 캐시가 맞는다. 질문마다 바뀌는 것(맥락, 대화, 질문)은 사용자 내용에만.</li>
 *   <li>근거 조문은 서버가 붙인 ID(L1 ...)로만 인용하게 하고, 서버가 검증한다 (ChatAnswerValidator).</li>
 *   <li>글자 칸의 maxLength 는 화면·저장에 맞춘 값이고, 지켜진다는 보장이 없어 서버가 한 번 더 자른다.</li>
 * </ul>
 */
final class ChatPrompt {

    static final String VERSION = "chat-v1";
    static final String SCHEMA_NAME = "chat_answer";

    static final int ANSWER_MAX = 1500;
    static final int ITEM_MAX = 200;
    static final int SUMMARY_MAX = 1000;
    static final int CITED_ID_MAX = 10;
    static final int TARGET_MAX = 20;

    /** 맥락에 넣는 글 길이 (토큰을 아끼려고 자름) */
    static final int SOURCE_TEXT_MAX = 600;
    static final int ORIGINAL_TEXT_MAX = 800;
    static final int FLAGGED_TEXT_MAX = 300;
    static final int PAST_MESSAGE_MAX = 500;
    static final int MAX_FLAGGED_ORIGINALS = 8;

    static final List<String> ANSWER_TYPES = List.of("ANSWERED", "PARTIAL", "OUT_OF_SCOPE");
    static final List<String> SOURCES = List.of("CURRENT_SCREEN", "MY_DOCUMENT", "APP_GUIDE");
    static final List<String> ACTION_TYPES = List.of("OPEN_SCREEN", "OPEN_CLAUSE", "OPEN_DOCUMENT_TYPE");

    /** 화면 번호 → 이름 (명세 화면 표기). 이름을 모르는 화면(S03~S05)은 넣지 않는다 */
    static final Map<String, String> SCREEN_NAMES = Map.ofEntries(
            Map.entry("S01", "홈"), Map.entry("S02", "문서 선택"), Map.entry("S06", "최종 확인"),
            Map.entry("S07", "분석 중"), Map.entry("S08", "분석 결과"), Map.entry("S09", "조항 상세"),
            Map.entry("S10", "챗봇"), Map.entry("S11", "비교 준비"), Map.entry("S12", "비교 결과"),
            Map.entry("S13", "일정 등록"), Map.entry("S14", "내 일정"), Map.entry("S15", "내 계약서"));

    static final String SYSTEM_PROMPT = """
            너는 문서AI 앱의 도우미다. 사용자의 계약서(근로계약서·임대차계약서)와 앱 사용법에 대한 질문에,
            이 요청에 들어 있는 자료만 보고 쉬운 한국어로 답한다. 법률 자문이나 법적 결론을 내리지 않는다.

            [쓸 수 있는 자료]
            - 현재 화면, 연결된 계약서의 분석 결과 요약(주요 조건, 조항 목록과 판정, 빠진 항목·기한), 보고 있는 조항 원문,
              비교 결과, 법령 자료([L1] 같은 ID 가 붙은 조문), 이전 대화 요약과 최근 대화, 아래 앱 사용법.
            - 자료에 없는 계약서 내용·법령·판례·숫자를 지어내지 않는다.
            - 연결된 계약서가 없거나 분석이 끝나지 않았으면 계약서 내용을 아는 것처럼 답하지 않는다.

            [법령 인용]
            - 법이 어떻게 정하는지 말할 때는 법령 자료에 있는 조문만 근거로 하고, 쓴 조문의 ID 를 citedIds 에 적는다.
              법령 자료에 없는 법 조문 이름(예: "○○법 제○조")은 답변에 쓰지 않는다.
            - 답변에 법이 어떻게 정하는지(법적 판단)를 썼으면 mentionsLaw 를 true 로 한다.
            - 법령 자료가 부족해 확실히 답할 수 없으면 answerType 을 PARTIAL 로 하고, partial.confirmed 에 계약서와 자료로
              확인한 것, partial.unknown 에 확인할 수 없는 것을 짧은 문장으로 적는다. PARTIAL 이 아니면 둘 다 빈 배열.

            [answerType]
            - ANSWERED: 자료로 답할 수 있음. PARTIAL: 일부만 답할 수 있음.
            - OUT_OF_SCOPE: 계약서·앱과 상관없는 질문이거나, 앱이 지원하지 않는 문서(근로계약서·임대차계약서가 아닌 문서)에 대한 질문.
              OUT_OF_SCOPE 면 이 앱이 무엇을 도와줄 수 있는지 한두 문장으로 안내한다.

            [이동 버튼 actions] 0~3개. 버튼은 화면 이동만 한다.
            - OPEN_CLAUSE: target 에 조항 목록의 clauseKey. 답변에서 말한 조항을 열어 볼 때.
            - OPEN_SCREEN: target 에 화면 번호. S01 홈, S08 분석 결과, S12 비교 결과, S13 일정 등록, S14 내 일정, S15 내 계약서 중에서만.
            - OPEN_DOCUMENT_TYPE: target 은 빈 문자열. 다른 문서를 새로 점검하려 할 때.
            - 대화만으로 문서를 보내거나 일정을 저장하지 않는다. 그렇게 해 달라고 하면 그 화면에서 직접 할 수 있다고 안내하고
              그 화면으로 가는 버튼만 준다.

            [usedSources] 답에 실제로 쓴 것만: CURRENT_SCREEN(현재 화면·보고 있는 조항·비교 결과),
            MY_DOCUMENT(연결된 계약서의 분석 결과), APP_GUIDE(앱 사용법). 법령은 citedIds 로만 적는다.

            [문체]
            - 쉬운 해요체로 3~6문장. 강조가 필요하면 <b>...</b> 만 쓴다.
            - "무효", "불법", "위법", "위반이다", "서명해도 된다" 같은 단정 표현을 쓰지 않는다.
              "~일 수 있어요", "확인이 필요해요"처럼 쓴다.
            - 사용자에게 보이는 글(answer, partial)에는 내부 표현을 쓰지 않는다: "제공된 근거", "제공된 자료", "규칙 결과",
              "법령 자료", "서버", "시스템", "지시", [L1] 같은 ID, c5 같은 조항 키.
              조항은 조 번호(예: "제7조")나 제목으로, 법은 "근로기준법 제20조"처럼 이름으로 부른다.
            - 사람 이름, 전화번호, 주민등록번호, 계좌번호, 개인 집 주소는 쓰지 않는다.

            [conversationSummary]
            - 이전 대화 요약, 최근 대화, 이번 질문과 답변을 합쳐 다음 질문에 필요한 내용만 300자 안팎으로 요약한다.
              개인 정보는 넣지 않는다.

            [앱 사용법]
            - S01 홈 → S02 문서 종류 선택 → 계약서 사진 찍기와 개인정보 가리기(가리기는 앱에서 하고 가린 사진만 올라감)
              → S06 최종 확인·분석 요청 → S07 분석 중(30초 안팎) → S08 분석 결과 → S09 조항 상세.
            - 분석 결과에서 계약서를 보관하면 S15 내 계약서에 남고, 일정 후보를 골라 일정으로 저장할 수 있다(S13 일정 등록,
              S14 내 일정). 일정이 다가오면 앱 안 알림이 온다.
            - 바뀐 계약서는 새로 올려 분석한 뒤 S11 비교 준비 → S12 비교 결과에서 이전 계약서와 비교하고, 수정본으로 확정하거나
              확정하지 않을 수 있다.
            - 분석 결과는 법률 자문이 아니다. 애매하면 고용노동부 1350, 대한법률구조공단 132 에 물어볼 수 있다.
            """;

    private ChatPrompt() {
    }

    static GptRequest request(ChatContext ctx) {
        return GptRequest.of(SYSTEM_PROMPT, userContent(ctx), SCHEMA_NAME, schema());
    }

    /** 사용자 내용: ① 맥락(화면·계약서·분석·조항·비교·법령) ② 대화(요약·최근 대화)와 질문 */
    static List<GptContentPart> userContent(ChatContext ctx) {
        StringBuilder c = new StringBuilder();
        c.append("[현재 화면] ").append(ctx.screen());
        String screenName = SCREEN_NAMES.get(ctx.screen());
        if (screenName != null) {
            c.append(" (").append(screenName).append(')');
        }
        c.append('\n');

        c.append("\n[연결된 계약서] ");
        if (ctx.linkedDocument() == null) {
            c.append("없음 (일반 질문)\n");
        } else {
            c.append(orDash(ctx.linkedDocument().title())).append('\n');
        }
        AnalysisChatInfo a = ctx.analysis();
        if (ctx.pendingStatus() == AnalysisStatus.ANALYZING) {
            c.append("- 이 계약서는 지금 분석 중이다. 새 분석 결과는 아직 없다.\n");
        } else if (ctx.pendingStatus() == AnalysisStatus.FAILED) {
            c.append("- 이 계약서의 최근 분석은 실패했다 (사진을 다시 찍거나 다시 분석 요청 필요).\n");
        }
        if (ctx.linkedDocument() != null && a == null) {
            c.append("- 완료된 분석 결과가 없다. 계약서 내용은 모른다.\n");
        }
        if (a != null) {
            appendAnalysis(c, a);
        }
        if (ctx.focusClause() != null) {
            AnalysisChatInfo.Clause f = ctx.focusClause();
            c.append("\n[보고 있는 조항] ").append(f.clauseKey()).append(" | ").append(orDash(f.articleNo())).append(" | ")
                    .append(orDash(f.title())).append(" | ").append(f.pageNo()).append("쪽\n");
            c.append("원문:\n\"\"\"\n").append(f.originalText() == null ? "(읽지 못함)"
                    : TextTruncator.truncate(f.originalText(), ORIGINAL_TEXT_MAX)).append("\n\"\"\"\n");
        }
        if (ctx.comparison() != null) {
            appendComparison(c, ctx.comparison());
        }
        c.append("\n[법령 자료] (인용할 수 있는 ID)\n");
        if (ctx.sources().isEmpty()) {
            c.append("- 없음\n");
        }
        for (ChatContext.Source s : ctx.sources()) {
            c.append('[').append(s.id()).append("] ").append(s.heading()).append('\n')
                    .append(TextTruncator.truncate(s.text(), SOURCE_TEXT_MAX)).append('\n');
        }

        StringBuilder q = new StringBuilder();
        q.append("[이전 대화 요약]\n").append(ctx.previousSummary() == null || ctx.previousSummary().isBlank()
                ? "- 없음" : ctx.previousSummary()).append('\n');
        q.append("\n[최근 대화]\n");
        if (ctx.recent().isEmpty()) {
            q.append("- 없음\n");
        }
        for (ChatContext.PastMessage m : ctx.recent()) {
            q.append(m.role() == ChatRole.USER ? "사용자: " : "도우미: ")
                    .append(TextTruncator.truncate(m.content(), PAST_MESSAGE_MAX)).append('\n');
        }
        q.append("\n[질문]\n").append(ctx.question()).append('\n');
        return List.of(GptContentPart.text(c.toString()), GptContentPart.text(q.toString()));
    }

    private static void appendAnalysis(StringBuilder c, AnalysisChatInfo a) {
        c.append("\n[분석 결과 요약] 문서 종류: ").append(a.documentTypeCode()).append('\n');
        c.append("주요 조건:\n");
        if (a.keyTerms().isEmpty()) {
            c.append("- 없음\n");
        }
        a.keyTerms().forEach(k -> c.append("- ").append(k.label()).append(": ").append(orDash(k.value()))
                .append(" (").append(keyTermState(k.state())).append(")\n"));
        if (!a.findings().isEmpty()) {
            c.append("빠진 항목·기한:\n");
            a.findings().forEach(f -> c.append("- ").append("MISSING_ITEM".equals(f.kind()) ? "빠진 항목 " : "기한 ")
                    .append(f.name()).append(": ").append(f.description())
                    .append(f.dueDate() == null ? "" : " (" + f.dueDate() + ")").append('\n'));
        }
        c.append("조항 목록: clauseKey | 조 번호 | 제목 | 판정 | 결과 제목 | 한 줄 요약\n");
        for (AnalysisChatInfo.Clause cl : a.clauses()) {
            c.append("- ").append(cl.clauseKey()).append(" | ").append(orDash(cl.articleNo())).append(" | ")
                    .append(orDash(cl.title())).append(" | ").append(verdict(cl.verdict())).append(" | ")
                    .append(orDash(cl.headline())).append(" | ").append(orDash(cl.oneLine())).append('\n');
        }
        int flagged = 0;
        for (AnalysisChatInfo.Clause cl : a.clauses()) {
            if (!"NEEDS_CHECK".equals(cl.verdict()) && !"UNCERTAIN".equals(cl.verdict())) {
                continue;
            }
            if (flagged++ >= MAX_FLAGGED_ORIGINALS) {
                break;
            }
            c.append("\n확인이 필요한 조항 ").append(cl.clauseKey()).append(" (").append(orDash(cl.articleNo())).append(")\n");
            if (cl.easyExplanation() != null) {
                c.append("쉬운 설명: ").append(cl.easyExplanation()).append('\n');
            }
            if (cl.whyCheck() != null) {
                c.append("확인이 필요한 이유: ").append(cl.whyCheck()).append('\n');
            }
            if ("UNCERTAIN".equals(cl.verdict())) {
                c.append("(근거 조문을 찾지 못한 조항이라 법적으로 어떤지는 말하지 않는다)\n");
            }
            if (cl.originalText() != null) {
                c.append("원문: \"").append(TextTruncator.truncate(cl.originalText(), FLAGGED_TEXT_MAX)).append("\"\n");
            }
        }
    }

    private static void appendComparison(StringBuilder c, ChatContext.Comparison cmp) {
        c.append("\n[비교 결과] 상태: ").append(cmp.status()).append('\n');
        if (cmp.changes().isEmpty()) {
            c.append("- 바뀐 곳 없음\n");
        }
        for (Change ch : cmp.changes()) {
            c.append("- ").append(ch.type()).append(" | ").append(ch.label()).append(" | 이전: ").append(orDash(ch.before()))
                    .append(" | 수정: ").append(orDash(ch.after()));
            if (ch.note() != null) {
                c.append(" | ").append(ch.note());
            }
            c.append('\n');
        }
        if (cmp.unchangedSummary() != null) {
            c.append("그대로인 곳: ").append(cmp.unchangedSummary()).append('\n');
        }
    }

    private static String verdict(String v) {
        return switch (v) {
            case "NEEDS_CHECK" -> "확인 필요";
            case "UNCERTAIN" -> "확인 필요 (근거 조문 없음)";
            case "OK" -> "문제 표시 없음";
            case "UNREADABLE" -> "읽지 못함";
            default -> v;
        };
    }

    private static String keyTermState(String s) {
        return switch (s) {
            case "OK" -> "확인됨";
            case "NEEDS_CHECK" -> "확인 필요";
            case "MISSING" -> "계약서에 없음";
            case "UNREADABLE" -> "읽지 못함";
            case "MASKED" -> "가린 곳";
            default -> s;
        };
    }

    // ---- 출력 스키마 (strict: 모든 칸 required, additionalProperties false) ----

    static Map<String, Object> schema() {
        Map<String, Object> partial = object(
                "confirmed", Map.of("type", "array", "items", string(ITEM_MAX)),
                "unknown", Map.of("type", "array", "items", string(ITEM_MAX)));
        Map<String, Object> action = object(
                "type", Map.of("type", "string", "enum", ACTION_TYPES),
                "target", string(TARGET_MAX));
        return object(
                "answerType", Map.of("type", "string", "enum", ANSWER_TYPES),
                "answer", string(ANSWER_MAX),
                "citedIds", Map.of("type", "array", "items", string(CITED_ID_MAX)),
                "usedSources", Map.of("type", "array", "items", Map.of("type", "string", "enum", SOURCES)),
                "mentionsLaw", Map.of("type", "boolean"),
                "partial", partial,
                "actions", Map.of("type", "array", "items", action),
                "conversationSummary", string(SUMMARY_MAX));
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

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
