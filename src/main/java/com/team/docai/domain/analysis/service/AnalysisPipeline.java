package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 백그라운드 분석 흐름. GPT 를 부르는 단계가 이 안에서 돌기 때문에 {@code @Transactional} 을 붙이지 않는다
 * (CLAUDE.md Service 규칙의 예외). 저장은 AnalysisWriter 의 짧은 트랜잭션으로 한다.
 * <ol>
 *   <li>실행 중 목록에 넣는다 (heartbeat 스케줄러가 15초마다 갱신. 보통은 Launcher 가 대기열에 넣기 전에 이미 넣어 둠)</li>
 *   <li>단계({@link AnalysisStepHandler})를 {@code @Order} 순서로 하나씩: 시작 때 진행 단계 저장, 분석이 이미 끝났으면 멈춤</li>
 *   <li>모두 끝나면 조건부 COMPLETED. JUDGING 조항이 남아 바뀌지 않으면 FAILED · TEMPORARY_ERROR</li>
 *   <li>단계 예외: AnalysisFailedException 은 그 사유로, GptCallException·그 밖의 예외는 TEMPORARY_ERROR 로 FAILED</li>
 * </ol>
 * 임시: 단계 빈이 하나도 없으면(지금 운영 상태) "분석 단계 미구현"으로 FAILED · TEMPORARY_ERROR 처리한다.
 * 5단계 작업에서 실제 단계가 들어오면 이 분기는 타지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisPipeline {

    private final ObjectProvider<AnalysisStepHandler> stepProvider;
    private final AnalysisWriter analysisWriter;
    private final RunningAnalysisRegistry registry;

    public void run(AnalysisRunContext ctx) {
        Long analysisId = ctx.analysisId();
        registry.start(analysisId);
        try {
            runSteps(ctx);
        } catch (AnalysisFailedException e) {
            log.info("분석 실패: analysisId={}, reason={}, message={}", analysisId, e.getReason(), e.getMessage());
            failQuietly(ctx, e.getReason(), e.getFailPageNo(), e.getDetectedTypeName());
        } catch (GptCallException e) {
            log.warn("분석 중 GPT 호출 실패: analysisId={}, reason={}, attempts={}", analysisId, e.getReason(), e.getAttempts());
            failQuietly(ctx, AnalysisFailReason.TEMPORARY_ERROR, null, null);
        } catch (RuntimeException e) {
            log.error("분석 중 오류: analysisId={}", analysisId, e);
            failQuietly(ctx, AnalysisFailReason.TEMPORARY_ERROR, null, null);
        } finally {
            registry.finish(analysisId);
        }
    }

    private void runSteps(AnalysisRunContext ctx) {
        List<AnalysisStepHandler> steps = stepProvider.orderedStream().toList();
        if (steps.isEmpty()) {
            // 임시 (5단계에서 사라짐): 실제 분석 단계가 아직 없다
            log.error("분석 단계 미구현: 등록된 AnalysisStepHandler 가 없어 실패 처리함. analysisId={}", ctx.analysisId());
            failQuietly(ctx, AnalysisFailReason.TEMPORARY_ERROR, null, null);
            return;
        }
        for (AnalysisStepHandler step : steps) {
            if (!analysisWriter.advance(ctx.analysisId(), step.progressStep())) {
                log.info("분석이 이미 끝나 있어 멈춤: analysisId={}, step={}", ctx.analysisId(), step.getClass().getSimpleName());
                return;
            }
            step.execute(ctx);
        }
        if (!analysisWriter.complete(ctx)) {
            // 이미 FAILED 로 바뀌었거나, 판정 중(JUDGING) 조항이 남아 완료할 수 없음. 앞의 경우는 아래 fail 이 0행
            if (analysisWriter.fail(ctx, AnalysisFailReason.TEMPORARY_ERROR, null, null)) {
                log.warn("판정 중 조항이 남아 완료하지 못하고 실패 처리함: analysisId={}", ctx.analysisId());
            }
        }
    }

    /** 실패 저장이 DB 오류로 안 되면 기록만 남긴다. 실행 목록에서 빠져 heartbeat 가 멈추므로 멈춘 분석 정리가 처리한다. */
    private void failQuietly(AnalysisRunContext ctx, AnalysisFailReason reason, Integer pageNo, String detectedTypeName) {
        try {
            analysisWriter.fail(ctx, reason, pageNo, detectedTypeName);
        } catch (RuntimeException e) {
            log.error("분석 실패 저장 실패 (멈춘 분석 정리가 처리함): analysisId={}", ctx.analysisId(), e);
        }
    }
}
