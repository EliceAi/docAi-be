package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRequestedEvent;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.global.config.AnalysisAsyncConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 분석 요청 트랜잭션이 커밋된 뒤에만 파이프라인을 전용 스레드 풀에 넣는다.
 * 커밋 전에 시작하면 백그라운드가 아직 보이지 않는 분석 행을 읽게 되고, 롤백되면 없는 분석을 돌리게 된다.
 * <ol>
 *   <li>요청 스레드에서 동기로 실행 중 목록에 넣는다. 대기열에서 차례를 기다리는 동안에도 heartbeat 가 갱신되어
 *       멈춘 분석으로 오인되지 않는다.</li>
 *   <li>스레드 풀에 넣는다. 풀과 대기열이 가득 차 거절되면 목록에서 빼고 바로 FAILED · TEMPORARY_ERROR (실패 알림 포함).
 *       커밋이 끝난 뒤라 실패 저장은 새 트랜잭션으로 한다.</li>
 * </ol>
 */
@Slf4j
@Component
public class AnalysisPipelineLauncher {

    private final AnalysisPipeline pipeline;
    private final RunningAnalysisRegistry registry;
    private final AnalysisWriter analysisWriter;
    private final Executor executor;

    public AnalysisPipelineLauncher(AnalysisPipeline pipeline, RunningAnalysisRegistry registry,
                                    AnalysisWriter analysisWriter,
                                    @Qualifier(AnalysisAsyncConfig.ANALYSIS_EXECUTOR) Executor executor) {
        this.pipeline = pipeline;
        this.registry = registry;
        this.analysisWriter = analysisWriter;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(AnalysisRequestedEvent event) {
        AnalysisRunContext ctx = event.context();
        registry.start(ctx.analysisId());
        try {
            executor.execute(() -> pipeline.run(ctx));
        } catch (RejectedExecutionException e) {
            registry.finish(ctx.analysisId());
            log.warn("분석 스레드 풀이 가득 차 실패 처리함: analysisId={}", ctx.analysisId());
            try {
                analysisWriter.failInNewTransaction(ctx, AnalysisFailReason.TEMPORARY_ERROR);
            } catch (RuntimeException failError) {
                log.error("거절된 분석 실패 저장 실패 (멈춘 분석 정리가 처리함): analysisId={}", ctx.analysisId(), failError);
            }
        }
    }
}
