package com.team.docai.domain.analysis.entity;

/**
 * analysis_clause.verdict (ck_clause_verdict). JUDGING: 4단계 저장 ~ 7단계 판정 사이의 판정 중.
 * JUDGING 이 남아 있으면 분석을 COMPLETED 로 바꾸지 않고, 조회 API 에도 내보내지 않는다.
 */
public enum ClauseVerdict {
    JUDGING,
    OK,
    NEEDS_CHECK,
    UNCERTAIN,
    UNREADABLE
}
