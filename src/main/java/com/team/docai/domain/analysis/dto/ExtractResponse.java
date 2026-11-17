package com.team.docai.domain.analysis.dto;

import com.team.docai.domain.analysis.entity.LawHint;

import java.util.List;

/**
 * GPT 1차(EXTRACT) 묶음 하나의 응답. 출력 스키마는 ExtractPrompt.schema() 와 필드가 1:1 로 같다
 * (GptClient 가 모르는 필드·빠진 필드를 스키마 불일치로 본다). 값이 없을 수 있는 칸은 null.
 *
 * @param documentTitle 문서에 인쇄된 제목 (예: 표준근로계약서). 없으면 null
 * @param contractDate  계약일(서명일) YYYY-MM-DD. 없으면 null
 * @param typeCheck     고른 문서 종류와 같은 문서인지
 * @param wageOnlyDocument 임금만 정한 별도 문서(연봉계약서·임금계약서처럼 근로시간·휴일 등은 다른 근로계약서에 있다고 보이는 문서)인지.
 *                      병합 후 analysis.extraction_json 의 최상위 "wageOnlyDocument"(boolean)로 남는다 (6단계가 읽음)
 * @param pages         이 묶음의 쪽마다 읽기 상태
 * @param clauses       읽은 순서대로 모든 조항
 * @param keyTerms      주요 조건 (항목마다 하나)
 * @param dates         계약서에 적힌 날짜
 */
public record ExtractResponse(
        String documentTitle,
        String contractDate,
        TypeCheck typeCheck,
        boolean wageOnlyDocument,
        List<PageRead> pages,
        List<Clause> clauses,
        List<KeyTerm> keyTerms,
        List<DateItem> dates
) {

    /** wageOnlyDocument 없이 만든다 (false). 테스트·이전 결과용 */
    public ExtractResponse(String documentTitle, String contractDate, TypeCheck typeCheck, List<PageRead> pages,
                           List<Clause> clauses, List<KeyTerm> keyTerms, List<DateItem> dates) {
        this(documentTitle, contractDate, typeCheck, false, pages, clauses, keyTerms, dates);
    }

    /** @param detectedTypeName 다르면 실제 문서 종류 이름, 같으면 null */
    public record TypeCheck(boolean matchesSelected, String detectedTypeName) {
    }

    /** @param readState READ(거의 다 읽음) / PARTIAL(일부 흐림·잘림) / UNREADABLE(글자를 거의 못 읽음) */
    public record PageRead(int pageNo, String readState) {
    }

    /**
     * @param clauseKey                 이 응답 안에서만 쓰는 키 (c1, c2 ...). 서버가 병합 때 새로 매긴다
     * @param continuesFromPreviousPage 묶음 첫 쪽 맨 위 조항이 앞 쪽(이 묶음에 없는 쪽)에서 이어지는지
     * @param continuesToNextPage       묶음 끝 쪽 맨 아래 조항이 다음 쪽(이 묶음에 없는 쪽)으로 이어지는지
     * @param issueTag                  꼬리표 코드 또는 null
     */
    public record Clause(
            String clauseKey,
            int pageNo,
            String articleNo,
            String title,
            String originalText,
            String oneLine,
            String location,
            boolean continuesFromPreviousPage,
            boolean continuesToNextPage,
            boolean issueFound,
            String issueSummary,
            String issueTag,
            List<LawHint> lawHints,
            List<String> searchKeywords,
            List<String> relatedClauseKeys
    ) {
    }

    /**
     * @param state WRITTEN(값 있음) / MISSING(적혀 있지 않음) / MASKED(가려져 못 읽음) / UNREADABLE(흐려 못 읽음)
     */
    public record KeyTerm(
            String label,
            String state,
            String value,
            ValueJson valueJson,
            String clauseKey,
            String evidence
    ) {
    }

    /**
     * 계산용 값. 항목에 맞는 칸만 채우고 나머지는 null (서버가 항목별 모양으로 다시 고른다).
     *
     * @param basePay      임금: 최저임금 비교에 쓸 금액. 기본급(+ 매월 고정 지급 수당), 연장·야간·휴일 수당은 뺌. 구분이 안 되면 null
     * @param monthlyHours 근로시간: 계약서에 적힌 월 기준시간 (예: 월 209시간, 통상임금 기준시간 수 209)
     */
    public record ValueJson(
            String type,
            Long amount,
            Long basePay,
            Double hoursPerDay,
            Double daysPerWeek,
            Double monthlyHours,
            String start,
            String end
    ) {
        /** 선택 칸(basePay, monthlyHours) 없이 만든다. 테스트·이전 결과용 */
        public ValueJson(String type, Long amount, Double hoursPerDay, Double daysPerWeek, String start, String end) {
            this(type, amount, null, hoursPerDay, daysPerWeek, null, start, end);
        }
    }

    public record DateItem(String label, String date, String clauseKey) {
    }
}
