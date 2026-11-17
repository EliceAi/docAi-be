package com.team.docai.domain.analysis.service;

import com.team.docai.global.config.AnalysisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * heartbeat 갱신과 멈춘 분석 정리를 분석 전용 스케줄러(스레드 docai.analysis.scheduler-threads 개)에서 주기적으로 돌린다.
 * 앱의 다른 {@code @Scheduled} 작업과 스레드를 나누지 않아, 다른 작업이 오래 걸려도 heartbeat 가 밀리지 않는다.
 * 스케줄러는 이 빈이 직접 만들고 닫는다 (TaskScheduler 빈으로 내보내면 {@code @Scheduled} 기본 스케줄러로 잡힐 수 있음).
 * docai.analysis.scheduler.enabled=false 면 이 빈만 만들지 않는다 (테스트가 같은 DB 의 다른 행을 바꾸지 않게).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "docai.analysis.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisLivenessScheduler implements SmartLifecycle {

    private final AnalysisLiveness liveness;
    private final AnalysisProperties properties;
    private volatile ThreadPoolTaskScheduler scheduler;

    public AnalysisLivenessScheduler(AnalysisLiveness liveness, AnalysisProperties properties) {
        this.liveness = liveness;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(properties.schedulerThreads());
        s.setThreadNamePrefix("analysis-liveness-");
        s.initialize();
        s.scheduleWithFixedDelay(liveness::heartbeat, properties.heartbeatInterval());
        s.scheduleWithFixedDelay(liveness::failStaleAnalyses,
                Instant.now().plus(properties.staleCheckInterval()), properties.staleCheckInterval());
        scheduler = s;
        log.info("분석 스케줄 시작: threads={}, heartbeat={}, staleCheck={}",
                properties.schedulerThreads(), properties.heartbeatInterval(), properties.staleCheckInterval());
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    /** 실행 중인 스케줄러 (테스트에서 스레드 수 확인용) */
    ThreadPoolTaskScheduler scheduler() {
        return scheduler;
    }
}
