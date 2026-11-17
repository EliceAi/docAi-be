package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRequestInfo;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisRequestServiceTest {

    static final UUID USER = UUID.randomUUID();
    static final AnalysisRequestInfo RUNNING =
            new AnalysisRequestInfo(88L, 12L, AnalysisStatus.ANALYZING, AnalysisStep.READING_TEXT, 0, 1);

    AnalysisRequestRateLimiter limiter = mock(AnalysisRequestRateLimiter.class);
    AnalysisRequestWriter writer = mock(AnalysisRequestWriter.class);
    AnalysisRequestService service = new AnalysisRequestService(limiter, writer);

    /** Spring 이 Hibernate 의 제약 위반을 바꿔 던지는 모양 (제약 이름은 Hibernate 가 PostgreSQL 오류에서 뽑음) */
    static DataIntegrityViolationException violation(String constraint) {
        SQLException sql = new SQLException("ERROR: duplicate key value violates unique constraint \"" + constraint + "\"", "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sql, constraint));
    }

    @Test
    void 횟수_제한에_걸리면_계약서를_건드리지_않는다() {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).check(USER);

        assertThatThrownBy(() -> service.request(USER, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
        verify(writer, never()).createOrGetRunning(any(), any());
    }

    @Test
    void 분석_중_UNIQUE_위반은_새_트랜잭션으로_다시_불러_먼저_만든_분석을_준다() {
        when(writer.createOrGetRunning(USER, 12L)).thenThrow(violation("uq_analysis_running")).thenReturn(RUNNING);

        assertThat(service.request(USER, 12L)).isEqualTo(RUNNING);
        verify(writer, times(2)).createOrGetRunning(USER, 12L);
    }

    @Test
    void 메시지에_인덱스_이름이_있어도_제약_이름이_다르면_그대로_던진다() {
        SQLException sql = new SQLException("ERROR: ... uq_analysis_running ...", "23505");
        when(writer.createOrGetRunning(USER, 12L)).thenThrow(new DataIntegrityViolationException("uq_analysis_running",
                new ConstraintViolationException("uq_analysis_running", sql, "uq_chunk_no")));

        assertThatThrownBy(() -> service.request(USER, 12L)).isInstanceOf(DataIntegrityViolationException.class);
        verify(writer, times(1)).createOrGetRunning(USER, 12L);
    }

    @Test
    void 제약_이름을_모르는_위반은_그대로_던진다() {
        when(writer.createOrGetRunning(USER, 12L)).thenThrow(new DataIntegrityViolationException("uq_analysis_running"));

        assertThatThrownBy(() -> service.request(USER, 12L)).isInstanceOf(DataIntegrityViolationException.class);
        verify(writer, times(1)).createOrGetRunning(USER, 12L);
    }

    @Test
    void 다른_제약_위반은_그대로_던진다() {
        when(writer.createOrGetRunning(USER, 12L)).thenThrow(violation("uq_chunk_no"));

        assertThatThrownBy(() -> service.request(USER, 12L)).isInstanceOf(DataIntegrityViolationException.class);
        verify(writer, times(1)).createOrGetRunning(USER, 12L);
    }

    /** PostgreSQL 드라이버 예외(PSQLException)의 모양: getServerErrorMessage().getConstraint() */
    public static class FakeServerMessage {
        private final String constraint;
        public FakeServerMessage(String constraint) { this.constraint = constraint; }
        public String getConstraint() { return constraint; }
    }

    public static class FakePsqlException extends SQLException {
        private final FakeServerMessage message;
        public FakePsqlException(String constraint) {
            super("ERROR: duplicate key", "23505");
            this.message = constraint == null ? null : new FakeServerMessage(constraint);
        }
        public FakeServerMessage getServerErrorMessage() { return message; }
    }

    static DataIntegrityViolationException violation(String driverConstraint, String hibernateConstraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement",
                        new FakePsqlException(driverConstraint), hibernateConstraint));
    }

    @Test
    void 드라이버가_준_제약_이름을_Hibernate_값보다_먼저_본다() {
        assertThat(AnalysisRequestService.violatedConstraint(violation("uq_analysis_running", "uq_chunk_no")))
                .isEqualTo("uq_analysis_running");
        assertThat(AnalysisRequestService.isRunningDuplicate(violation("uq_chunk_no", "uq_analysis_running"))).isFalse();
    }

    @Test
    void 드라이버_값이_없으면_Hibernate_제약_이름을_쓴다() {
        assertThat(AnalysisRequestService.isRunningDuplicate(violation(null, "uq_analysis_running"))).isTrue();
        assertThat(AnalysisRequestService.isRunningDuplicate(violation(null, null))).isFalse();
    }
}
