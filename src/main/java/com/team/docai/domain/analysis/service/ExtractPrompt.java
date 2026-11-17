package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.client.GptContentPart;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.law.dto.IssueTagInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GPT 1차(EXTRACT) 지시문·출력 스키마·사용자 내용. 아키텍처 3단계.
 * <ul>
 *   <li>지시문과 스키마는 문서 종류마다 고정(꼬리표·주요 조건 목록 포함)이라 같은 종류끼리 프롬프트 캐시가 맞는다.
 *       묶음마다 바뀌는 것(쪽 번호, 이미지)은 사용자 내용에만 넣는다.</li>
 *   <li>글자 칸의 maxLength 는 저장 칸 길이와 같다 (설계 6번). 지켜진다는 보장은 없어 저장 전에 서버가 한 번 더 처리한다.</li>
 *   <li>최종 판단은 하지 않는다. 의심 표시(issueFound)와 검색어까지만.</li>
 * </ul>
 * 이 내용을 바꾸면 {@link #VERSION} 을 올린다 (analysis.prompt_version 으로 추적).
 */
final class ExtractPrompt {

    /** 이 지시문·스키마 버전. docai.analysis.prompt-version 의 1차 부분과 같게 둔다 */
    static final String VERSION = "extract-v2";
    static final String SCHEMA_NAME = "contract_extract";

    static final List<String> READ_STATES = List.of("READ", "PARTIAL", "UNREADABLE");
    static final List<String> TERM_STATES = List.of("WRITTEN", "MISSING", "MASKED", "UNREADABLE");

    /** 계약서 등에서 온 글이 아닌 GPT 가 짓는 짧은 칸의 길이 (저장 칸과 같음) */
    static final int DOCUMENT_TITLE_MAX = 100;
    static final int ISSUE_SUMMARY_MAX = 300;
    static final int DATE_LABEL_MAX = 50;
    static final int KEYWORD_MAX = 30;
    static final int LAW_NAME_MAX = 100;
    static final int LAW_ARTICLE_MAX = 30;

    private ExtractPrompt() {
    }

    static String systemPrompt(String documentTypeCode, List<IssueTagInfo> tags) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                너는 한국어 계약서 사진을 읽어 정해진 JSON 으로 옮겨 적는 도구다. 법적 결론은 내리지 않는다.
                사진은 사용자가 개인정보를 미리 가린 것이다. 가린 칸(검은·흰 상자, 모자이크)의 내용은 추측하지 않는다.

                [공통 규칙]
                - 계약서에 적힌 글은 고치거나 요약하지 말고 보이는 그대로 옮긴다. 보이지 않는 글을 지어내지 않는다.
                - 개인의 이름, 전화번호, 주민등록번호, 계좌번호, 개인 집 주소가 보이면 어느 칸에서든 "○○○"로 바꿔 적는다.
                  회사 이름·근무 장소처럼 개인이 아닌 정보는 그대로 둔다.
                - 네가 직접 쓰는 칸(oneLine, location, issueSummary)은 짧은 한국어로 쓰고 "무효", "불법", "위반이다",
                  "서명해도 된다" 같은 단정 표현을 쓰지 않는다.

                [문서 종류 확인 typeCheck]
                - 사용자가 고른 종류와 같은 종류의 문서면 matchesSelected = true, detectedTypeName = null.
                - 다른 종류의 문서(다른 계약서, 계약서가 아닌 문서)면 false 와 실제 종류 이름(예: "주택 임대차계약서", "영수증").
                - 사진을 거의 읽지 못해 종류를 알 수 없으면 matchesSelected = true 로 두고 그 쪽을 UNREADABLE 로 표시한다.

                [임금 전용 문서 wageOnlyDocument]
                - 연봉계약서·임금계약서처럼 임금만 정하고 근로시간·휴일 등은 다른 근로계약서에 있다고 보이는 문서면 true, 아니면 false.

                [쪽 읽기 상태 pages] 이 묶음의 쪽마다 하나씩.
                - READ: 거의 다 읽음. PARTIAL: 일부가 흐리거나 잘리거나 접혀 못 읽음. UNREADABLE: 글자를 거의 못 읽음.
                - 가린 칸 때문에 못 읽은 것은 PARTIAL 로 보지 않는다.

                [조항 clauses] 문서의 모든 조항을 읽는 순서대로, 하나도 빠뜨리거나 합치거나 나누지 않는다.
                - "제N조" 하나가 조항 하나다. 조 번호가 없는 특약·추가 사항은 문장 묶음마다 조항 하나다.
                  문서 제목, 당사자 표기란, 서명·날인란, 작성일 줄은 조항이 아니다.
                - clauseKey: 이 응답 안에서 c1, c2 ... 순서대로 붙인다.
                - pageNo: 그 조항이 시작하는 쪽 번호 (사진 앞에 적힌 "N쪽").
                - articleNo: 인쇄된 조 번호(예: "제7조"). 없으면 null.
                - title: 문서에 인쇄된 조항 제목만(예: "임금"). 없으면 null. 지어 붙이지 않는다.
                - originalText: 조항 전체 글(항·호 포함)을 그대로. 글자를 거의 못 읽은 조항이면 null.
                - oneLine: 목차용 한 줄 요약. location: 쪽 안 위치 설명(예: "1쪽 가운데").
                - continuesFromPreviousPage: 이 묶음 첫 쪽 맨 위 조항이 앞 쪽에서 이어진 글이면 true.
                - continuesToNextPage: 이 묶음 끝 쪽 맨 아래 조항이 다음 쪽으로 이어지면 true.
                - issueFound: 근로자·임차인에게 불리하거나 법에 어긋날 소지가 있어 보이는 조항만 true. 최종 판단이 아니라 "의심" 표시다.
                  법이 정한 내용을 그대로 옮긴 표준 문구는 false.
                - issueFound 가 true 일 때만 issueSummary(무엇이 의심되는지 한 문장), lawHints(떠오르는 법령 이름과 조 번호),
                  searchKeywords(법령 검색어 2~5개)를 채운다. false 면 issueSummary = null, 두 배열은 빈 배열.
                - issueTag: 아래 꼬리표 중 딱 맞는 것이 있을 때만 그 코드, 없으면 null. 꼬리표가 없어도 의심 조항은 표시한다.
                - relatedClauseKeys: 함께 봐야 하는 다른 조항의 clauseKey (예: 교육비 반환 조항 ↔ 계약 기간 조항). 없으면 빈 배열.

                [주요 조건 keyTerms] 아래 항목마다 정확히 하나씩, 이 묶음에서 찾은 대로.
                - state: WRITTEN(값이 적혀 있음) / MISSING(이 묶음에 적혀 있지 않음) / MASKED(가려져 못 읽음) / UNREADABLE(흐려 못 읽음).
                - value: 화면에 보여 줄 값. 계약서 표현을 그대로 짧게 (예: "시급 10,000원"). WRITTEN 이 아니면 null.
                - valueJson: 항목 설명에 적힌 칸만 채우고 나머지는 null. 날짜는 YYYY-MM-DD, 금액은 원 단위 정수.
                  WRITTEN 이 아니거나 숫자로 바꿀 수 없으면 모든 칸 null.
                - clauseKey: 값이 나온 조항의 clauseKey. evidence: 값이 나온 원문 문장 그대로. 없으면 둘 다 null.

                [날짜]
                - contractDate: 계약 체결일(작성일·서명일) YYYY-MM-DD. 없으면 null.
                - dates: 계약서에 적힌 구체적 날짜 (계약 시작일, 종료일, 잔금일 등). label 은 짧은 이름, date 는 YYYY-MM-DD,
                  clauseKey 는 나온 조항. 날짜가 아닌 것(매월 25일 같은 반복)은 넣지 않는다.
                - documentTitle: 문서 맨 위에 인쇄된 제목. 없으면 null.
                """);
        sb.append("\n[고른 문서 종류] ").append(ExtractCatalog.documentTypeName(documentTypeCode))
                .append(" (").append(documentTypeCode).append(")\n");
        sb.append("\n[꼬리표 목록] 코드: 이름\n");
        if (tags.isEmpty()) {
            sb.append("- (없음. issueTag 는 항상 null)\n");
        }
        for (IssueTagInfo tag : tags) {
            sb.append("- ").append(tag.code()).append(": ").append(tag.name()).append('\n');
        }
        sb.append("\n[주요 조건 항목] label: 설명\n");
        for (ExtractCatalog.Term term : ExtractCatalog.terms(documentTypeCode)) {
            sb.append("- ").append(term.label()).append(": ").append(term.hint());
            if (!term.valueKeys().isEmpty()) {
                sb.append(" (valueJson 칸: ").append(String.join(", ", term.valueKeys())).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 사용자 내용: 묶음 안내 + 쪽마다 "N쪽" 글자와 이미지 */
    static List<GptContentPart> userContent(int pageFrom, int pageTo, int totalPages, List<PageImage> images) {
        List<GptContentPart> parts = new ArrayList<>();
        parts.add(GptContentPart.text("전체 " + totalPages + "쪽 중 " + pageFrom + "~" + pageTo
                + "쪽 묶음이다. 각 사진 앞에 쪽 번호가 있다."));
        for (PageImage image : images) {
            parts.add(GptContentPart.text(image.pageNo() + "쪽"));
            parts.add(GptContentPart.image(image.data(), image.mimeType()));
        }
        return parts;
    }

    record PageImage(int pageNo, byte[] data, String mimeType) {
        @Override
        public String toString() {
            return "PageImage[pageNo=" + pageNo + ", bytes=" + data.length + "]";
        }
    }

    // ---- 출력 스키마 (strict: 모든 칸 required, additionalProperties false, 없을 수 있는 값은 null 허용) ----

    static Map<String, Object> schema(String documentTypeCode, List<IssueTagInfo> tags) {
        List<String> labels = ExtractCatalog.terms(documentTypeCode).stream().map(ExtractCatalog.Term::label).toList();

        Map<String, Object> typeCheck = object(
                "matchesSelected", Map.of("type", "boolean"),
                "detectedTypeName", nullableString(Analysis.DETECTED_TYPE_NAME_MAX_LENGTH));
        Map<String, Object> page = object(
                "pageNo", Map.of("type", "integer"),
                "readState", Map.of("type", "string", "enum", READ_STATES));
        Map<String, Object> lawHint = object(
                "law", string(LAW_NAME_MAX),
                "article", string(LAW_ARTICLE_MAX));
        List<Object> tagValues = new ArrayList<>(tags.stream().map(IssueTagInfo::code).toList());
        tagValues.add(null);
        Map<String, Object> issueTag = tags.isEmpty()
                ? Map.of("type", "null")
                : Map.of("type", List.of("string", "null"), "enum", tagValues);
        Map<String, Object> clause = object(
                "clauseKey", string(AnalysisClause.CLAUSE_KEY_MAX_LENGTH),
                "pageNo", Map.of("type", "integer"),
                "articleNo", nullableString(AnalysisClause.ARTICLE_NO_MAX_LENGTH),
                "title", nullableString(AnalysisClause.TITLE_MAX_LENGTH),
                "originalText", Map.of("type", List.of("string", "null")),
                "oneLine", string(AnalysisClause.ONE_LINE_MAX_LENGTH),
                "location", nullableString(AnalysisClause.LOCATION_MAX_LENGTH),
                "continuesFromPreviousPage", Map.of("type", "boolean"),
                "continuesToNextPage", Map.of("type", "boolean"),
                "issueFound", Map.of("type", "boolean"),
                "issueSummary", nullableString(ISSUE_SUMMARY_MAX),
                "issueTag", issueTag,
                "lawHints", Map.of("type", "array", "items", lawHint),
                "searchKeywords", Map.of("type", "array", "items", string(KEYWORD_MAX)),
                "relatedClauseKeys", Map.of("type", "array", "items", string(AnalysisClause.CLAUSE_KEY_MAX_LENGTH)));
        List<Object> wageTypes = new ArrayList<>(ExtractCatalog.WAGE_TYPES);
        wageTypes.add(null);
        Map<String, Object> valueJson = object(
                "type", Map.of("type", List.of("string", "null"), "enum", wageTypes),
                "amount", Map.of("type", List.of("integer", "null")),
                "basePay", Map.of("type", List.of("integer", "null")),
                "hoursPerDay", Map.of("type", List.of("number", "null")),
                "daysPerWeek", Map.of("type", List.of("number", "null")),
                "monthlyHours", Map.of("type", List.of("number", "null")),
                "start", nullableString(10),
                "end", nullableString(10));
        Map<String, Object> keyTerm = object(
                "label", labels.isEmpty() ? string(AnalysisKeyTerm.LABEL_MAX_LENGTH)
                        : Map.of("type", "string", "enum", labels),
                "state", Map.of("type", "string", "enum", TERM_STATES),
                "value", nullableString(AnalysisKeyTerm.VALUE_MAX_LENGTH),
                "valueJson", valueJson,
                "clauseKey", nullableString(AnalysisClause.CLAUSE_KEY_MAX_LENGTH),
                "evidence", nullableString(AnalysisKeyTerm.EVIDENCE_MAX_LENGTH));
        Map<String, Object> date = object(
                "label", string(DATE_LABEL_MAX),
                "date", string(10),
                "clauseKey", nullableString(AnalysisClause.CLAUSE_KEY_MAX_LENGTH));
        return object(
                "documentTitle", nullableString(DOCUMENT_TITLE_MAX),
                "contractDate", nullableString(10),
                "typeCheck", typeCheck,
                "wageOnlyDocument", Map.of("type", "boolean"),
                "pages", Map.of("type", "array", "items", page),
                "clauses", Map.of("type", "array", "items", clause),
                "keyTerms", Map.of("type", "array", "items", keyTerm),
                "dates", Map.of("type", "array", "items", date));
    }

    private static Map<String, Object> object(Object... nameAndSchema) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < nameAndSchema.length; i += 2) {
            properties.put((String) nameAndSchema[i], nameAndSchema[i + 1]);
        }
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", "object");
        object.put("properties", properties);
        object.put("required", List.copyOf(properties.keySet()));
        object.put("additionalProperties", false);
        return object;
    }

    private static Map<String, Object> string(int maxLength) {
        return Map.of("type", "string", "maxLength", maxLength);
    }

    private static Map<String, Object> nullableString(int maxLength) {
        return Map.of("type", Arrays.asList("string", "null"), "maxLength", maxLength);
    }
}
