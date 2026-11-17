package com.team.docai.domain.analysis.entity;

/**
 * analysis_key_term.state. MASKED: 사용자가 가려서 읽지 않음.
 */
public enum KeyTermState {
    OK,
    NEEDS_CHECK,
    MISSING,
    UNREADABLE,
    MASKED
}
