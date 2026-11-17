package com.team.docai.domain.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관련 조항 (analysis_clause_relation). 기본 키 (clause_id, related_clause_id).
 * analysis_id 는 두 조항이 같은 분석인지 DB 복합 FK 가 확인하는 데 쓴다.
 * 저장 전에 연결 쌍을 중복 제거한다 (ERD).
 */
@Entity
@Table(name = "analysis_clause_relation")
@IdClass(AnalysisClauseRelationId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisClauseRelation {

    @Id
    @Column(nullable = false)
    private Long clauseId;

    @Id
    @Column(nullable = false)
    private Long relatedClauseId;

    @Column(nullable = false)
    private Long analysisId;

    @Builder
    private AnalysisClauseRelation(Long analysisId, Long clauseId, Long relatedClauseId) {
        this.analysisId = analysisId;
        this.clauseId = clauseId;
        this.relatedClauseId = relatedClauseId;
    }

    public static AnalysisClauseRelation create(Long analysisId, Long clauseId, Long relatedClauseId) {
        if (clauseId != null && clauseId.equals(relatedClauseId)) {
            throw new IllegalArgumentException("조항은 자기 자신과 연결할 수 없습니다.");
        }
        return AnalysisClauseRelation.builder()
                .analysisId(analysisId)
                .clauseId(clauseId)
                .relatedClauseId(relatedClauseId)
                .build();
    }
}
