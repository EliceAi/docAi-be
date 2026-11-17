package com.team.docai.domain.analysis.service;

import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisRequestRateLimiterTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-17T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void 한도까지는_통과하고_넘으면_RATE_LIMITED() {
        AnalysisRequestRateLimiter limiter = new AnalysisRequestRateLimiter(new MutableClock(), 2);
        UUID user = UUID.randomUUID();

        limiter.check(user);
        limiter.check(user);

        assertThatThrownBy(() -> limiter.check(user))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void 사용자별로_따로_센다() {
        AnalysisRequestRateLimiter limiter = new AnalysisRequestRateLimiter(new MutableClock(), 1);
        limiter.check(UUID.randomUUID());

        assertThatCode(() -> limiter.check(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void 일분이_지나면_다시_허용한다() {
        MutableClock clock = new MutableClock();
        AnalysisRequestRateLimiter limiter = new AnalysisRequestRateLimiter(clock, 1);
        UUID user = UUID.randomUUID();
        limiter.check(user);
        assertThatThrownBy(() -> limiter.check(user)).isInstanceOf(BusinessException.class);

        clock.now = clock.now.plus(Duration.ofSeconds(60));

        assertThatCode(() -> limiter.check(user)).doesNotThrowAnyException();
    }
}
