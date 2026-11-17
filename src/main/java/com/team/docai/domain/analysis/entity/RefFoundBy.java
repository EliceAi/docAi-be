package com.team.docai.domain.analysis.entity;

/**
 * analysis_clause_ref.found_by (ck_ref_found_by). 근거를 어떤 방법으로 찾았는지.
 */
public enum RefFoundBy {
    RULE,
    TAG_MAP,
    AI_HINT,
    SEARCH,
    NEED_MORE
}
