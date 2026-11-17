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

import java.time.LocalDate;

/**
 * 규칙 결과 (analysis_finding): 빠진 필수 항목(MISSING_ITEM)과 법정 기한(DEADLINE). 5단계 숫자 규칙이 만든다.
 */
@Entity
@Table(name = "analysis_finding")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisFinding {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int DESCRIPTION_MAX_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long findingId;

    @Column(nullable = false)
    private Long analysisId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FindingKind kind;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(nullable = false, length = DESCRIPTION_MAX_LENGTH)
    private String description;

    /** DEADLINE 의 기한. 계약일이 없으면 null. MISSING_ITEM 은 항상 null */
    private LocalDate dueDate;

    private Long lawArticleId;

    @Column(nullable = false)
    private int sortOrder;

    @Builder
    private AnalysisFinding(Long analysisId, FindingKind kind, String name, String description, LocalDate dueDate,
                            Long lawArticleId, int sortOrder) {
        this.analysisId = analysisId;
        this.kind = kind;
        this.name = name;
        this.description = description;
        this.dueDate = dueDate;
        this.lawArticleId = lawArticleId;
        this.sortOrder = sortOrder;
    }

    public static AnalysisFinding create(Long analysisId, FindingKind kind, String name, String description,
                                         LocalDate dueDate, Long lawArticleId, int sortOrder) {
        return AnalysisFinding.builder()
                .analysisId(analysisId)
                .kind(kind)
                .name(name)
                .description(description)
                .dueDate(dueDate)
                .lawArticleId(lawArticleId)
                .sortOrder(sortOrder)
                .build();
    }
}
