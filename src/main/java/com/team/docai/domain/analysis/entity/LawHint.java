package com.team.docai.domain.analysis.entity;

/**
 * analysis_clause.law_hints 배열의 한 칸. GPT 1차가 떠올린 법령 (DB 확인 전). 예: {law: "근로기준법", article: "제20조"}
 */
public record LawHint(String law, String article) {
}
