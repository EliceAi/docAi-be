package com.team.docai.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * application.yml 의 docai.analysis 구역 (분석 요청·백그라운드 실행).
 * 분석 스케줄(heartbeat, 멈춘 분석 정리)을 켜고 끄는 것은 docai.analysis.scheduler.enabled (AnalysisLivenessScheduler 의 조건).
 *
 * @param promptVersion         analysis.prompt_version 에 저장할 프롬프트·출력 스키마 버전 (50자 이하)
 * @param pagesPerChunk         쪽 묶음 크기 (아키텍처 2단계: 5쪽씩)
 * @param requestsPerMinute     사용자별 1분 동안 받는 분석 요청 수. 넘으면 429 RATE_LIMITED
 * @param executorThreads       백그라운드 분석을 동시에 돌리는 스레드 수
 * @param executorQueueCapacity 스레드가 모두 바쁠 때 기다리게 하는 분석 수
 * @param staleAfter            heartbeat 가 이만큼 멈춘 ANALYZING 은 FAILED(TEMPORARY_ERROR)로 바꾼다
 * @param heartbeatInterval     heartbeat 갱신 주기
 * @param staleCheckInterval    멈춘 분석 검사 주기
 * @param schedulerThreads      분석 전용 스케줄러 스레드 수 (heartbeat 와 멈춘 분석 정리가 서로 막지 않게 2 이상)
 */
@ConfigurationProperties(prefix = "docai.analysis")
public record AnalysisProperties(
        @DefaultValue("extract-v3/judge-v1") String promptVersion,
        @DefaultValue("5") int pagesPerChunk,
        @DefaultValue("5") int requestsPerMinute,
        @DefaultValue("4") int executorThreads,
        @DefaultValue("100") int executorQueueCapacity,
        @DefaultValue("2m") Duration staleAfter,
        @DefaultValue("15s") Duration heartbeatInterval,
        @DefaultValue("30s") Duration staleCheckInterval,
        @DefaultValue("2") int schedulerThreads
) {
    public AnalysisProperties {
        if (promptVersion == null || promptVersion.isBlank() || promptVersion.length() > 50) {
            throw new IllegalArgumentException("docai.analysis.prompt-version 은 1~50자여야 합니다.");
        }
        if (pagesPerChunk < 1 || requestsPerMinute < 1 || executorThreads < 1 || executorQueueCapacity < 0) {
            throw new IllegalArgumentException("docai.analysis 설정값이 올바르지 않습니다.");
        }
        if (!positive(staleAfter) || !positive(heartbeatInterval) || !positive(staleCheckInterval)) {
            throw new IllegalArgumentException("docai.analysis 의 stale-after, heartbeat-interval, stale-check-interval 은 0보다 커야 합니다.");
        }
        if (schedulerThreads < 2) {
            throw new IllegalArgumentException("docai.analysis.scheduler-threads 는 2 이상이어야 합니다.");
        }
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isNegative() && !d.isZero();
    }
}
