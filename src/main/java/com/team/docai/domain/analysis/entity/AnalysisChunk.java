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

/**
 * 분석 쪽 묶음 (analysis_chunk). 약 5쪽씩, 경계 쪽은 다음 묶음과 겹칠 수 있다.
 */
@Entity
@Table(name = "analysis_chunk")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long chunkId;

    @Column(nullable = false)
    private Long analysisId;

    @Column(nullable = false)
    private int chunkNo;

    @Column(nullable = false)
    private int pageFrom;

    @Column(nullable = false)
    private int pageTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChunkStatus status;

    /** 이 묶음의 1차 응답 JSON 그대로 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String resultJson;

    @Column(nullable = false)
    private int retryCount;

    @Builder
    private AnalysisChunk(Long analysisId, int chunkNo, int pageFrom, int pageTo, ChunkStatus status, int retryCount) {
        this.analysisId = analysisId;
        this.chunkNo = chunkNo;
        this.pageFrom = pageFrom;
        this.pageTo = pageTo;
        this.status = status;
        this.retryCount = retryCount;
    }

    /** 분석 요청 때 묶음 수만큼 PENDING 으로 만든다. */
    public static AnalysisChunk create(Long analysisId, int chunkNo, int pageFrom, int pageTo) {
        return AnalysisChunk.builder()
                .analysisId(analysisId)
                .chunkNo(chunkNo)
                .pageFrom(pageFrom)
                .pageTo(pageTo)
                .status(ChunkStatus.PENDING)
                .retryCount(0)
                .build();
    }
}
