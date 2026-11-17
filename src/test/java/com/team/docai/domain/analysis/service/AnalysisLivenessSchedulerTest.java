package com.team.docai.domain.analysis.service;

import com.team.docai.global.config.AnalysisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AnalysisLivenessSchedulerTest {

    static AnalysisProperties props(Duration heartbeat, Duration staleCheck, int threads) {
        return new AnalysisProperties("extract-v1/judge-v1", 5, 5, 4, 100, Duration.ofMinutes(2),
                heartbeat, staleCheck, threads);
    }

    @Test
    void 전용_스케줄러에서_heartbeat와_멈춘_분석_정리를_주기적으로_돌린다() throws Exception {
        AnalysisLiveness liveness = mock(AnalysisLiveness.class);
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch heartbeats = new CountDownLatch(2);
        CountDownLatch staleChecks = new CountDownLatch(1);
        doAnswer(inv -> { threads.add(Thread.currentThread().getName()); heartbeats.countDown(); return null; })
                .when(liveness).heartbeat();
        doAnswer(inv -> { threads.add(Thread.currentThread().getName()); staleChecks.countDown(); return null; })
                .when(liveness).failStaleAnalyses();
        AnalysisLivenessScheduler scheduler = new AnalysisLivenessScheduler(liveness,
                props(Duration.ofMillis(50), Duration.ofMillis(80), 2));

        scheduler.start();
        try {
            assertThat(scheduler.scheduler().getPoolSize()).isGreaterThanOrEqualTo(1);
            assertThat(scheduler.scheduler().getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(heartbeats.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(staleChecks.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threads).allMatch(name -> name.startsWith("analysis-liveness-"));
        } finally {
            scheduler.stop();
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void 설정으로_끄면_분석_스케줄_빈만_만들지_않는다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(AnalysisLiveness.class, () -> mock(AnalysisLiveness.class))
                .withBean(AnalysisProperties.class, () -> props(Duration.ofSeconds(15), Duration.ofSeconds(30), 2))
                .withUserConfiguration(AnalysisLivenessScheduler.class);

        runner.withPropertyValues("docai.analysis.scheduler.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AnalysisLivenessScheduler.class));
        runner.run(ctx -> assertThat(ctx).hasSingleBean(AnalysisLivenessScheduler.class));
    }

    @Test
    void 스케줄러_스레드는_2개_이상이어야_한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> props(Duration.ofSeconds(15), Duration.ofSeconds(30), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
