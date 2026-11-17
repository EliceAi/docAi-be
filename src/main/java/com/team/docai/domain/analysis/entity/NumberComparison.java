package com.team.docai.domain.analysis.entity;

/**
 * analysis_clause.comparison_json. 숫자 규칙 비교 (계약서 시급 &lt; 최저임금).
 * 모양은 분석 결과 조회 priorityClauses[].comparison 과 같다.
 */
public record NumberComparison(String leftLabel, String leftValue, String operator,
                               String rightLabel, String rightValue) {
}
