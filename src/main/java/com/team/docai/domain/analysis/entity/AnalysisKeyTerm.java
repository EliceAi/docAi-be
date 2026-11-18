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
 * label 은 고정 목록(ExtractCatalog). value·evidence 는 계약서에서 온 값이라 자르지 않는다: value 가 넘으면 저장하지 않고
 * state = NEEDS_CHECK, evidence 원문 인용이 넘으면 null (ExtractMerger, 기록은 analysis.validation_log_json).
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

    /** 미인식 항목 입력을 받을 수 있는 칸인가: 읽지 못한 칸(UNREADABLE)만. 가린 칸(MASKED)·빠진 칸·읽은 칸은 아니다 */
    public boolean isManuallyEnterable() {
        return state == KeyTermState.UNREADABLE;
    }

    /**
     * 미인식 항목 입력. 사용자가 직접 넣은 값으로 바꾸고 출처를 MANUAL 로 둔다.
     * state 는 UNREADABLE 그대로 둔다 (확인된 값처럼 보이지 않게). 계산용 값(value_json)은 비워
     * AI 가 읽은 값처럼 숫자 규칙에 쓰이지 않게 한다. 조항 판정은 다시 계산하지 않는다.
     */
    public void enterManually(String manualValue) {
        if (!isManuallyEnterable()) {
            throw new IllegalStateException("읽지 못한 칸만 직접 입력할 수 있다: " + state);
        }
        this.value = manualValue;
        this.valueJson = null;
        this.source = KeyTermSource.MANUAL;
    }
}
