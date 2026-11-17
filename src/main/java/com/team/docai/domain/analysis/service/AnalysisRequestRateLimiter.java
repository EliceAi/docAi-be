package com.team.docai.domain.analysis.service;

import com.team.docai.global.config.AnalysisProperties;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 분석 요청 횟수 제한: 사용자별 1분 고정 창에 docai.analysis.requests-per-minute 번까지 (넘으면 429 RATE_LIMITED).
 * 명세에는 숫자가 없어 global/auth/AnonymousRateLimiter(IP당 1분 10회)와 같은 방식으로 사용자 ID 기준으로 둔다.
 * 서버 메모리에만 있으므로 서버가 여러 대면 대마다 따로 센다. 이미 분석 중이라 같은 분석을 돌려주는 요청도 센다.
 */
@Component
public class AnalysisRequestRateLimiter {

    private static final int WINDOW_SECONDS = 60;
    /** 창 수 상한. 넘으면 새 사용자는 창이 비워질 때까지 거절한다 (메모리 보호) */
    private static final int MAX_WINDOWS = 10_000;

    private final Clock clock;
    private final int limit;
    private final Map<UUID, Window> windows = new HashMap<>();

    @Autowired
    public AnalysisRequestRateLimiter(AnalysisProperties properties) {
        this(Clock.systemUTC(), properties.requestsPerMinute());
    }

    AnalysisRequestRateLimiter(Clock clock, int limit) {
        this.clock = clock;
        this.limit = limit;
    }

    public synchronized void check(UUID userId) {
        Instant now = clock.instant();
        windows.entrySet().removeIf(e -> !now.isBefore(e.getValue().start.plusSeconds(WINDOW_SECONDS)));
        Window window = windows.get(userId);
        if (window == null) {
            if (windows.size() >= MAX_WINDOWS) {
                throw new BusinessException(ErrorCode.RATE_LIMITED);
            }
            window = new Window(now);
            windows.put(userId, window);
        }
        if (window.count >= limit) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        window.count++;
    }

    private static final class Window {
        private final Instant start;
        private int count;

        private Window(Instant start) {
            this.start = start;
        }
    }
}
