package com.team.docai.domain.analysis.service;

import com.team.docai.global.config.AnalysisProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisLivenessTest {

    AnalysisWriter writer = mock(AnalysisWriter.class);
    RunningAnalysisRegistry registry = new RunningAnalysisRegistry();
    AnalysisLiveness scheduler = new AnalysisLiveness(registry, writer,
            new AnalysisProperties("extract-v1/judge-v1", 5, 5, 4, 100, Duration.ofMinutes(2),
            Duration.ofSeconds(15), Duration.ofSeconds(30), 2));

    @Test
    void heartbeat는_이_서버가_돌리는_분석만_갱신한다() {
        registry.start(1L);
        registry.start(2L);

        scheduler.heartbeat();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(writer).touchHeartbeat(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void 돌리는_분석이_없으면_갱신하지_않는다() {
        scheduler.heartbeat();
        verify(writer, never()).touchHeartbeat(any());
    }

    @Test
    void 멈춘_분석을_2분_기준으로_하나씩_실패_처리하고_하나가_실패해도_계속한다() {
        when(writer.findStaleIds(any())).thenReturn(List.of(5L, 6L));
        when(writer.failStale(eq(5L), any())).thenThrow(new IllegalStateException("x"));
        OffsetDateTime before = OffsetDateTime.now().minusMinutes(2);

        scheduler.failStaleAnalyses();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(writer).findStaleIds(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(before, OffsetDateTime.now().minusMinutes(2));
        verify(writer).failStale(6L, cutoff.getValue());
    }
}
