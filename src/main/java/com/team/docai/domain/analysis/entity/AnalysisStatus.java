package com.team.docai.domain.analysis.entity;

/**
 * analysis.status (ck_analysis_status). ANALYZING 에서 COMPLETED/FAILED 로 바꾸는 것은
 * AnalysisRepository 의 조건부 업데이트로만 한다.
 */
public enum AnalysisStatus {
    ANALYZING,
    COMPLETED,
    FAILED
}
