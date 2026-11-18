package com.team.docai.domain.analysis.service;

import java.util.List;
import java.util.Map;

/**
 * 1차 추출에서 문서 종류마다 고정된 값: 문서 이름(프롬프트용)과 주요 조건 항목.
 * 주요 조건 label 은 analysis_key_term.label(30자)과 UNIQUE (analysis_id, label) 이므로 GPT 가 고르지 않고 이 목록으로 정한다.
 * value_json 모양은 항목마다 고정 (설계 29번, ERD): 임금 {type, amount}, 근로시간 {hoursPerDay, daysPerWeek},
 * 계약기간 {start, end}, 금액 항목 {amount}. 나머지 항목은 value_json 없음.
 * 선택 칸(값이 있을 때만 넣음): 임금 basePay(최저임금 비교 금액), 근로시간 monthlyHours(월 기준시간). 6단계와 약속한 이름.
 */
public final class ExtractCatalog {

    /** @param valueKeys value_json 에 남길 칸 (ExtractResponse.ValueJson 필드 이름). 비면 value_json 없음 */
    record Term(String label, List<String> valueKeys, String hint) {
    }

    /** value_json 에 값이 있을 때만 넣는 칸 (없으면 키도 넣지 않아 기존 모양 {type, amount} 등을 유지) */
    static final java.util.Set<String> OPTIONAL_VALUE_KEYS = java.util.Set.of("basePay", "monthlyHours");

    static final List<String> WAGE_TYPES = List.of("HOURLY", "DAILY", "WEEKLY", "MONTHLY", "ANNUAL");

    private static final Map<String, String> NAMES = Map.of(
            "EMPLOYMENT", "근로계약서",
            "LEASE", "주택 임대차계약서");

    private static final Map<String, List<Term>> TERMS = Map.of(
            "EMPLOYMENT", List.of(
                    new Term("계약기간", List.of("start", "end"), "근로계약 시작일과 종료일. 기간을 정하지 않았으면 end 는 null"),
                    new Term("근무장소", List.of(), "일하는 곳"),
                    new Term("업무", List.of(), "맡는 일"),
                    new Term("근로시간", List.of("hoursPerDay", "daysPerWeek", "monthlyHours"),
                            "시업·종업 시각, 휴게시간, 주 근무일. hoursPerDay 는 휴게 뺀 하루 소정근로시간. monthlyHours 는 \"월 209시간\","
                                    + " \"통상임금 기준시간 수 209\"처럼 월 기준시간이 적혀 있을 때만. 근로시간 조항이 없어도 월 기준시간이"
                                    + " 적혀 있으면 WRITTEN 으로 그 문장을 value 에 적는다"),
                    new Term("휴일", List.of(), "주휴일 등 쉬는 날"),
                    new Term("임금", List.of("type", "amount", "basePay"),
                            "임금. type 은 HOURLY/DAILY/WEEKLY/MONTHLY/ANNUAL, amount 는 그 단위의 총액(원 단위 정수)."
                                    + " basePay 는 최저임금 비교에 쓸 같은 단위의 금액: 임금 표가 있으면 기본급(+ 매월 고정 지급되는 수당)만,"
                                    + " 연장·야간·휴일 수당(약정연장근로수당 등)은 뺀다. 구분이 안 되면 null"),
                    new Term("임금 지급일", List.of(), "임금을 주는 날과 방법"),
                    new Term("연차휴가", List.of(), "연차 유급휴가")),
            "LEASE", List.of(
                    new Term("보증금", List.of("amount"), "보증금. amount 는 원 단위 정수"),
                    new Term("월세", List.of("amount"), "월 차임. 없으면 MISSING. amount 는 원 단위 정수"),
                    new Term("관리비", List.of("amount"), "월 관리비. amount 는 원 단위 정수"),
                    new Term("계약기간", List.of("start", "end"), "임대차 시작일과 종료일"),
                    new Term("잔금일", List.of(), "잔금 치르는 날")));

    private ExtractCatalog() {
    }

    public static String documentTypeName(String documentTypeCode) {
        return NAMES.getOrDefault(documentTypeCode, documentTypeCode);
    }

    static List<Term> terms(String documentTypeCode) {
        return TERMS.getOrDefault(documentTypeCode, List.of());
    }
}
