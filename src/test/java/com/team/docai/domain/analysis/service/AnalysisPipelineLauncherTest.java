package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRequestedEvent;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.global.config.AnalysisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AnalysisPipelineLauncherTest {

    static final AnalysisRunContext CTX = new AnalysisRunContext(88L, UUID.randomUUID(), 12L, 30L, "EMPLOYMENT");

    AnalysisPipeline pipeline = mock(AnalysisPipeline.class);
    AnalysisWriter writer = mock(AnalysisWriter.class);
    RunningAnalysisRegistry registry = new RunningAnalysisRegistry();
    AnalysisLiveness liveness = new AnalysisLiveness(registry, writer,
            new AnalysisProperties("extract-v1/judge-v1", 5, 5, 4, 100, Duration.ofMinutes(2),
                    Duration.ofSeconds(15), Duration.ofSeconds(30), 2));

    /** 제출만 받아 두고 돌리지 않는 실행기 (스레드가 모두 바빠 대기열에 있는 상태) */
    static class QueueingExecutor implements Executor {
        final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }
    }

    @Test
    void 대기열에_있는_동안에도_실행_목록에_있어_heartbeat가_갱신된다() {
        QueueingExecutor executor = new QueueingExecutor();
        AnalysisPipelineLauncher launcher = new AnalysisPipelineLauncher(pipeline, registry, writer, executor);

        launcher.onRequested(new AnalysisRequestedEvent(CTX));

        assertThat(executor.queued).hasSize(1);
        verify(pipeline, never()).run(any());
        assertThat(registry.isRunning(88L)).isTrue();
        liveness.heartbeat();
        verify(writer).touchHeartbeat(List.of(88L));

        executor.queued.get(0).run();
        verify(pipeline).run(CTX);
    }

    @Test
    void 제출이_거절되면_실행_목록에서_빼고_바로_FAILED_TEMPORARY_ERROR() {
        Executor rejecting = command -> {
            throw new TaskRejectedException("가득 참");
        };
        AnalysisPipelineLauncher launcher = new AnalysisPipelineLauncher(pipeline, registry, writer, rejecting);

        launcher.onRequested(new AnalysisRequestedEvent(CTX));

        verify(writer).failInNewTransaction(CTX, AnalysisFailReason.TEMPORARY_ERROR);
        verify(pipeline, never()).run(any());
        assertThat(registry.isRunning(88L)).isFalse();
    }
}
