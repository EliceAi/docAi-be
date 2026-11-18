package com.team.docai.domain.analysis.entity;

/**
 * analysis_clause.basis_type. RULE 은 8단계 검증에서 UNCERTAIN 으로 낮추지 않는다.
 */
public enum BasisType {
    RULE,
    AI_INTERPRETATION;

    /**
     * 조회 응답용 값. 저장값이 없으면(2차 판단을 거치지 않은 OK·UNREADABLE 조항) AI_INTERPRETATION.
     * 저장된 행은 바꾸지 않고 응답을 만들 때만 채운다.
     */
    public static BasisType orDefault(BasisType basisType) {
        return basisType == null ? AI_INTERPRETATION : basisType;
    }
}
