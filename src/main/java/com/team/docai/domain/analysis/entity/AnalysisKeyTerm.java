package com.team.docai.domain.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * 주요 조건 (analysis_key_term). (analysis_id, label) 은 UNIQUE (uq_key_term).
 * GPT 가 채우는 글자 칸(label, value, evidence)은 저장 전에 *_MAX_LENGTH 로 잘라서 넣는다.
 */
@Entity
@Table(name = "analysis_key_term")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisKeyTerm {

    public static final int LABEL_MAX_LENGTH = 30;
    public static final int VALUE_MAX_LENGTH = 200;
    public static final int EVIDENCE_MAX_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long keyTermId;

    @Column(nullable = false)
    private Long analysisId;

    /** 이 값이 나온 조항 (analysis_clause). 연관관계 대신 ID 로 둔다. */
    private Long clauseId;

    @Column(nullable = false, length = LABEL_MAX_LENGTH)
    private String label;

    @Column(length = VALUE_MAX_LENGTH)
    private String value;

    /** 계산용 값. 항목별 모양 고정 (임금 {type, amount}, 근로시간 {hoursPerDay, daysPerWeek}, 계약기간 {start, end}) */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> valueJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KeyTermState state;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private KeyTermSource source;

    @Column(length = EVIDENCE_MAX_LENGTH)
    private String evidence;

    @Column(nullable = false)
    private int sortOrder;

    @Builder
    private AnalysisKeyTerm(Long analysisId, Long clauseId, String label, String value, Map<String, Object> valueJson,
                            KeyTermState state, KeyTermSource source, String evidence, int sortOrder) {
        this.analysisId = analysisId;
        this.clauseId = clauseId;
        this.label = label;
        this.value = value;
        this.valueJson = valueJson;
        this.state = state;
        this.source = source;
        this.evidence = evidence;
        this.sortOrder = sortOrder;
    }

    public static AnalysisKeyTerm create(Long analysisId, Long clauseId, String label, String value,
                                         Map<String, Object> valueJson, KeyTermState state, KeyTermSource source,
                                         String evidence, int sortOrder) {
        return AnalysisKeyTerm.builder()
                .analysisId(analysisId)
                .clauseId(clauseId)
                .label(label)
                .value(value)
                .valueJson(valueJson)
                .state(state)
                .source(source)
                .evidence(evidence)
                .sortOrder(sortOrder)
                .build();
    }
}
