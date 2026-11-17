package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import lombok.Getter;

/**
 * 분석 단계가 분석을 실패로 끝낼 때 던진다 (예: 4단계 TYPE_MISMATCH, PAGE_UNREADABLE).
 * 파이프라인이 받아 FAILED 로 바꾸고 실패 알림을 만든다. 사용자 응답으로 나가지 않는다.
 *
 * @see AnalysisPipeline
 */
@Getter
public class AnalysisFailedException extends RuntimeException {

    private final AnalysisFailReason reason;
    private final Integer failPageNo;
    private final String detectedTypeName;

    public AnalysisFailedException(AnalysisFailReason reason, Integer failPageNo, String detectedTypeName, String message) {
        super(message);
        this.reason = reason;
        this.failPageNo = failPageNo;
        this.detectedTypeName = detectedTypeName;
    }
}
