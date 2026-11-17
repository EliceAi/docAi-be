package com.team.docai.domain.analysis.entity;

/**
 * analysis.progress_step (ck_analysis_step). 실패하면 마지막 단계를 그대로 둔다.
 */
public enum AnalysisStep {
    READING_TEXT,
    FINDING_TERMS,
    MATCHING_LAWS,
    SUMMARIZING,
    DONE
}
