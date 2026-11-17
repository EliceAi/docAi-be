package com.team.docai.domain.analysis.entity;

/**
 * analysis.fail_reason. FAILED 이면 반드시 있어야 한다 (ck_analysis_fail).
 */
public enum AnalysisFailReason {
    PAGE_UNREADABLE,
    TYPE_MISMATCH,
    TEMPORARY_ERROR
}
