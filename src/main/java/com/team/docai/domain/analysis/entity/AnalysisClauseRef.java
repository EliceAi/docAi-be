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

/**
 * 조항 근거 (analysis_clause_ref). 2차 호출에 준 근거와 인용 여부, AI 에게 보낸 내용의 스냅샷.
 * law_article_id 와 guidance_id 중 정확히 하나만 채운다 (ck_ref_one_target).
 */
@Entity
@Table(name = "analysis_clause_ref")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisClauseRef {

    public static final int REF_LABEL_MAX_LENGTH = 10;
    public static final int REF_TITLE_MAX_LENGTH = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long refId;

    @Column(nullable = false)
    private Long clauseId;

    /** 2차 호출에서 붙인 ID (L1, G3). 조항 안에서만 유일 (uq_clause_ref) */
    @Column(nullable = false, length = REF_LABEL_MAX_LENGTH)
    private String refLabel;

    private Long lawArticleId;

    private Long guidanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefFoundBy foundBy;

    /** AI 가 인용하고 검증을 통과했는지 (RULE 은 true) */
    @Column(nullable = false)
    private boolean cited;

    @Column(nullable = false, length = REF_TITLE_MAX_LENGTH)
    private String refTitle;

    @Column(nullable = false, columnDefinition = "text")
    private String refText;

    @Builder
    private AnalysisClauseRef(Long clauseId, String refLabel, Long lawArticleId, Long guidanceId, RefFoundBy foundBy,
                              boolean cited, String refTitle, String refText) {
        this.clauseId = clauseId;
        this.refLabel = refLabel;
        this.lawArticleId = lawArticleId;
        this.guidanceId = guidanceId;
        this.foundBy = foundBy;
        this.cited = cited;
        this.refTitle = refTitle;
        this.refText = refText;
    }

    public static AnalysisClauseRef create(Long clauseId, String refLabel, Long lawArticleId, Long guidanceId,
                                           RefFoundBy foundBy, boolean cited, String refTitle, String refText) {
        if ((lawArticleId == null) == (guidanceId == null)) {
            throw new IllegalArgumentException("lawArticleId 와 guidanceId 중 정확히 하나만 있어야 합니다.");
        }
        return AnalysisClauseRef.builder()
                .clauseId(clauseId)
                .refLabel(refLabel)
                .lawArticleId(lawArticleId)
                .guidanceId(guidanceId)
                .foundBy(foundBy)
                .cited(cited)
                .refTitle(refTitle)
                .refText(refText)
                .build();
    }
}
