package com.team.docai.domain.analysis.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이 서버에서 지금 파이프라인이 돌고 있는 분석 ID. heartbeat 스케줄러가 이 목록의 heartbeat_at 을 갱신한다.
 * 파이프라인 스레드가 GPT 응답을 기다리는 동안에도 갱신되게 하려고 파이프라인과 따로 둔다.
 * 서버가 죽으면 목록이 사라지고 heartbeat 가 멈춰, 멈춘 분석 정리가 FAILED 로 바꾼다.
 */
@Component
public class RunningAnalysisRegistry {

    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public void start(Long analysisId) {
        running.add(analysisId);
    }

    public void finish(Long analysisId) {
        running.remove(analysisId);
    }

    public boolean isRunning(Long analysisId) {
        return running.contains(analysisId);
    }

    public List<Long> snapshot() {
        return List.copyOf(running);
    }
}
