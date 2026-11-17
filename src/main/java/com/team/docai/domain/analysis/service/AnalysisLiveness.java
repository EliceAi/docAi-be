package com.team.docai.domain.analysis.service;

import com.team.docai.global.config.AnalysisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 분석 생존 확인 (ERD '실패 처리', 아키텍처 '중간에 끝나는 경우: 분석 중 서버 재시작').
 * <ul>
 *   <li>heartbeat: 15초마다 이 서버가 돌리는 분석의 heartbeat_at 갱신. 파이프라인 스레드와 따로 돌아
 *       GPT 응답을 기다리는 동안에도 갱신된다.</li>
 *   <li>멈춘 분석 정리: heartbeat 가 2분 넘게 멈춘 ANALYZING 을 하나씩 조건부로 FAILED · TEMPORARY_ERROR.</li>
 * </ul>
 * 이 클래스는 한 번 도는 일만 한다. 주기 실행은 AnalysisLivenessScheduler (끌 수 있음, 테스트는 이 빈을 직접 부른다).
 * 트랜잭션은 AnalysisWriter 의 메서드마다 따로.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisLiveness {

    private final RunningAnalysisRegistry registry;
    private final AnalysisWriter analysisWriter;
    private final AnalysisProperties properties;

    public void heartbeat() {
        List<Long> ids = registry.snapshot();
        if (ids.isEmpty()) {
            return;
        }
        try {
            analysisWriter.touchHeartbeat(ids);
        } catch (RuntimeException e) {
            log.warn("heartbeat 갱신 실패: count={}, error={}", ids.size(), e.toString());
        }
    }

    public void failStaleAnalyses() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.staleAfter());
        List<Long> staleIds;
        try {
            staleIds = analysisWriter.findStaleIds(cutoff);
        } catch (RuntimeException e) {
            log.warn("멈춘 분석 조회 실패: error={}", e.toString());
            return;
        }
        for (Long id : staleIds) {
            try {
                analysisWriter.failStale(id, cutoff);
            } catch (RuntimeException e) {
                log.warn("멈춘 분석 실패 처리 실패: analysisId={}, error={}", id, e.toString());
            }
        }
    }
}
