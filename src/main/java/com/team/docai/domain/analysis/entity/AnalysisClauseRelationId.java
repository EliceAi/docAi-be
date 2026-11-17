package com.team.docai.domain.analysis.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * analysis_clause_relation 의 복합 기본 키 (clause_id, related_clause_id).
 */
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AnalysisClauseRelationId implements Serializable {

    private Long clauseId;
    private Long relatedClauseId;
}
