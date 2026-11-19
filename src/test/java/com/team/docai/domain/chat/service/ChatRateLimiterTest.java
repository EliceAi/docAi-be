package com.team.docai.domain.chat.service;

import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AI 질문 횟수 제한: 사용자별 1분 창, 창 수 상한에 차면 오래된 창부터 지워 새 사용자를 막지 않는다 */
class ChatRateLimiterTest {

    static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    void 한도를_넘으면_429_RATE_LIMITED이고_1분이_지나면_다시_된다() {
        UUID user = UUID.randomUUID();
        ChatRateLimiter limiter = new ChatRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), 2);
        limiter.check(user);
        limiter.check(user);
        assertThatThrownBy(() -> limiter.check(user))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RATE_LIMITED);

        ChatRateLimiter later = new ChatRateLimiter(Clock.offset(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(61)), 2);
        assertThatCode(() -> later.check(user)).doesNotThrowAnyException();
    }

    @Test
    void 창_수_상한에_차면_처음_오는_사용자를_막지_않고_가장_오래된_창을_지운다() {
        ChatRateLimiter limiter = new ChatRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), 1, 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        limiter.check(first);
        limiter.check(second);

        assertThatCode(() -> limiter.check(third)).doesNotThrowAnyException();
        // first 의 창이 지워져 다시 셀 수 있다. second 는 남아 있어 한도에 걸린다
        assertThatCode(() -> limiter.check(first)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(third))
                .extracting("errorCode").isEqualTo(ErrorCode.RATE_LIMITED);
    }
}
