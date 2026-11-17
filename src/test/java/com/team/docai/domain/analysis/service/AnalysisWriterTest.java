package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.notification.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisWriterTest {

    static final UUID USER = UUID.randomUUID();
    static final AnalysisRunContext CTX = new AnalysisRunContext(88L, USER, 12L, 30L, "EMPLOYMENT");

    AnalysisRepository repository = mock(AnalysisRepository.class);
    ContractService contractService = mock(ContractService.class);
    NotificationService notificationService = mock(NotificationService.class);
    AnalysisWriter writer = new AnalysisWriter(repository, contractService, notificationService);

    @Test
    void 완료가_1행_바뀌면_최신_분석을_연결하고_완료_알림을_만든다() {
        when(repository.markCompleted(eq(88L), eq(AnalysisStatus.ANALYZING), any())).thenReturn(1);

        assertThat(writer.complete(CTX)).isTrue();

        verify(contractService).linkLatestAnalysis(30L, 88L);
        verify(notificationService).createAnalysisResult(USER, 88L, "ANALYSIS_DONE", AnalysisWriter.DONE_TITLE, null);
    }

    @Test
    void 완료가_0행이면_연결도_알림도_하지_않는다() {
        when(repository.markCompleted(eq(88L), eq(AnalysisStatus.ANALYZING), any())).thenReturn(0);

        assertThat(writer.complete(CTX)).isFalse();

        verifyNoInteractions(contractService, notificationService);
    }

    @Test
    void 실패가_1행_바뀌면_실패_알림을_만들고_최신_분석은_건드리지_않는다() {
        when(repository.markFailed(eq(88L), eq(AnalysisStatus.ANALYZING), eq(AnalysisFailReason.TEMPORARY_ERROR),
                isNull(), isNull(), any())).thenReturn(1);

        assertThat(writer.fail(CTX, AnalysisFailReason.TEMPORARY_ERROR, null, null)).isTrue();

        verify(notificationService).createAnalysisResult(eq(USER), eq(88L), eq("ANALYSIS_FAILED"),
                eq(AnalysisWriter.FAILED_TITLE), any());
        verifyNoInteractions(contractService);
    }

    @Test
    void 새_트랜잭션_실패도_1행_바뀌면_실패_알림을_만든다() {
        when(repository.markFailed(eq(88L), eq(AnalysisStatus.ANALYZING), eq(AnalysisFailReason.TEMPORARY_ERROR),
                isNull(), isNull(), any())).thenReturn(1);

        assertThat(writer.failInNewTransaction(CTX, AnalysisFailReason.TEMPORARY_ERROR)).isTrue();

        verify(notificationService).createAnalysisResult(eq(USER), eq(88L), eq("ANALYSIS_FAILED"),
                eq(AnalysisWriter.FAILED_TITLE), any());
    }

    @Test
    void 실패가_0행이면_알림을_만들지_않는다() {
        when(repository.markFailed(anyLong(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThat(writer.fail(CTX, AnalysisFailReason.TEMPORARY_ERROR, null, null)).isFalse();

        verifyNoInteractions(notificationService);
    }

    @Test
    void 실제_문서_종류_이름은_50자로_자른다() {
        when(repository.markFailed(anyLong(), any(), any(), any(), any(), any())).thenReturn(1);

        writer.fail(CTX, AnalysisFailReason.TYPE_MISMATCH, null, "가".repeat(60));

        verify(repository).markFailed(eq(88L), eq(AnalysisStatus.ANALYZING), eq(AnalysisFailReason.TYPE_MISMATCH),
                isNull(), eq("가".repeat(50)), any());
    }

    @Test
    void heartbeat_대상이_없으면_DB를_부르지_않는다() {
        assertThat(writer.touchHeartbeat(List.of())).isZero();
        verify(repository, never()).touchHeartbeat(any(), any());
    }

    @Test
    void 멈춘_분석은_TEMPORARY_ERROR로_조건부_실패() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(repository.markFailedIfStale(eq(88L), eq(cutoff), eq(AnalysisFailReason.TEMPORARY_ERROR), any())).thenReturn(1);

        assertThat(writer.failStale(88L, cutoff)).isTrue();
        verifyNoInteractions(contractService, notificationService);
    }
}
