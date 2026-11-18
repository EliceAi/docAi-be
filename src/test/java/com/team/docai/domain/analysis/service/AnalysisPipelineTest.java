package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptFailureReason;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.contract.service.ContractService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisPipelineTest {

    static final AnalysisRunContext CTX = new AnalysisRunContext(88L, UUID.randomUUID(), 12L, 30L, "EMPLOYMENT");

    AnalysisWriter writer = mock(AnalysisWriter.class);
    RunningAnalysisRegistry registry = new RunningAnalysisRegistry();
    ContractService contractService = mock(ContractService.class);
    List<String> calls = new ArrayList<>();
    /** 단계 안에서 본 실행 중 목록 (heartbeat 대상인지) */
    List<Boolean> runningDuringStep = new ArrayList<>();

    record FakeStep(String name, AnalysisStep progressStep, Consumer<AnalysisRunContext> body) implements AnalysisStepHandler {
        @Override public void execute(AnalysisRunContext context) { body.accept(context); }
    }

    FakeStep step(String name, AnalysisStep progress) {
        return new FakeStep(name, progress, ctx -> {
            calls.add(name);
            runningDuringStep.add(registry.isRunning(ctx.analysisId()));
        });
    }

    @SuppressWarnings("unchecked")
    AnalysisPipeline pipeline(AnalysisStepHandler... steps) {
        ObjectProvider<AnalysisStepHandler> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> List.of(steps).stream());
        return new AnalysisPipeline(provider, writer, registry, contractService);
    }

    @Test
    void 단계를_순서대로_돌리고_진행_단계를_저장한_뒤_완료한다() {
        when(writer.advance(anyLong(), any())).thenReturn(true);
        when(writer.complete(CTX)).thenReturn(true);

        pipeline(step("read", AnalysisStep.READING_TEXT), step("terms", AnalysisStep.FINDING_TERMS),
                step("laws", AnalysisStep.MATCHING_LAWS)).run(CTX);

        assertThat(calls).containsExactly("read", "terms", "laws");
        assertThat(runningDuringStep).containsOnly(true);
        InOrder order = inOrder(writer);
        order.verify(writer).advance(88L, AnalysisStep.READING_TEXT);
        order.verify(writer).advance(88L, AnalysisStep.FINDING_TERMS);
        order.verify(writer).advance(88L, AnalysisStep.MATCHING_LAWS);
        order.verify(writer).complete(CTX);
        verify(writer, never()).fail(any(), any(), any(), any());
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void 완료된_뒤에_분석이_정한_이름으로_계약서_이름을_넣는다() {
        when(writer.advance(anyLong(), any())).thenReturn(true);
        when(writer.complete(CTX)).thenReturn(true);
        when(writer.title(88L)).thenReturn("카페 알바 근로계약서");

        pipeline(step("read", AnalysisStep.READING_TEXT)).run(CTX);

        InOrder order = inOrder(writer, contractService);
        order.verify(writer).complete(CTX);
        order.verify(contractService).setTitleIfEmpty(12L, "카페 알바 근로계약서");
    }

    @Test
    void 이름_저장이_실패해도_완료된_분석을_실패로_바꾸지_않는다() {
        when(writer.advance(anyLong(), any())).thenReturn(true);
        when(writer.complete(CTX)).thenReturn(true);
        when(writer.title(88L)).thenReturn("이름");
        org.mockito.Mockito.doThrow(new IllegalStateException("잠금 실패")).when(contractService)
                .setTitleIfEmpty(anyLong(), any());

        pipeline(step("read", AnalysisStep.READING_TEXT)).run(CTX);

        verify(writer, never()).fail(any(), any(), any(), any());
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void 완료되지_않으면_계약서_이름을_넣지_않는다() {
        when(writer.advance(anyLong(), any())).thenReturn(true);
        when(writer.complete(CTX)).thenReturn(false);

        pipeline(step("read", AnalysisStep.READING_TEXT)).run(CTX);

        verify(contractService, never()).setTitleIfEmpty(anyLong(), any());
    }

    @Test
    void 단계_예외는_FAILED_TEMPORARY_ERROR이고_뒤_단계는_돌지_않는다() {
        when(writer.advance(anyLong(), any())).thenReturn(true);
        FakeStep boom = new FakeStep("boom", AnalysisStep.FINDING_TERMS, ctx -> {
            throw new IllegalStateException("저장 실패");
        });

        pipeline(step("read", AnalysisStep.READING_TEXT), boom, step("laws", AnalysisStep.MATCHING_LAWS)).run(CTX);

        assertThat(calls).containsExactly("read");
        verify(writer).fail(CTX, AnalysisFailReason.TEMPORARY_ERROR, null, null);
        verify(writer, never()).complete(any());
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void GPT_호출_실패는_TEMPORARY_ERROR() {
        when(writer.advance(anyLong(), any())).thenReturn(true);
        FakeStep gpt = new FakeStep("gpt", AnalysisStep.READING_TEXT, ctx -> {
            throw new GptCallException(GptFailureReason.TIMEOUT, "시간 초과", 2);
        });

        pipeline(gpt).run(CTX);

        verify(writer).fail(CTX, AnalysisFailReason.TEMPORARY_ERROR, null, null);
    }

    @Test
    void 단계가_정한_실패_사유는_그대로_저장한다() {
        when(writer.advance(anyLong(), any())).thenReturn(true);
        FakeStep mismatch = new FakeStep("merge", AnalysisStep.FINDING_TERMS, ctx -> {
            throw new AnalysisFailedException(AnalysisFailReason.TYPE_MISMATCH, null, "임대차계약서", "종류 다름");
        });

        pipeline(mismatch).run(CTX);

        verify(writer).fail(CTX, AnalysisFailReason.TYPE_MISMATCH, null, "임대차계약서");
    }

    @Test
    void 이미_끝난_분석이면_단계를_멈추고_아무것도_바꾸지_않는다() {
        when(writer.advance(88L, AnalysisStep.READING_TEXT)).thenReturn(true);
        when(writer.advance(88L, AnalysisStep.FINDING_TERMS)).thenReturn(false);

        pipeline(step("read", AnalysisStep.READING_TEXT), step("terms", AnalysisStep.FINDING_TERMS)).run(CTX);

        assertThat(calls).containsExactly("read");
        verify(writer, never()).complete(any());
        verify(writer, never()).fail(any(), any(), any(), any());
    }

    @Test
    void 완료로_바뀌지_않으면_판정_중_조항이_남은_것으로_보고_실패_처리한다() {
        when(writer.advance(anyLong(), any())).thenReturn(true);
        when(writer.complete(CTX)).thenReturn(false);

        pipeline(step("read", AnalysisStep.READING_TEXT)).run(CTX);

        verify(writer).fail(CTX, AnalysisFailReason.TEMPORARY_ERROR, null, null);
    }

    @Test
    void 단계_빈이_없으면_분석_단계_미구현으로_실패_처리한다() {
        pipeline().run(CTX);

        verify(writer).fail(CTX, AnalysisFailReason.TEMPORARY_ERROR, null, null);
        verify(writer, never()).advance(anyLong(), any());
        verify(writer, never()).complete(any());
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void 실패_저장마저_실패해도_실행_목록에서_빠진다() {
        when(writer.advance(anyLong(), any())).thenReturn(true);
        when(writer.fail(any(), any(), any(), any())).thenThrow(new IllegalStateException("DB 끊김"));
        FakeStep boom = new FakeStep("boom", AnalysisStep.READING_TEXT, ctx -> {
            throw new IllegalStateException("x");
        });

        pipeline(boom).run(CTX);

        assertThat(registry.snapshot()).isEmpty();
    }
}
