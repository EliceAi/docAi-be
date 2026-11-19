package com.team.docai.domain.chat.service;

import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI 질문 횟수 제한: 사용자별 1분 고정 창에 docai.chat.requests-per-minute 번까지 (기본 10, 넘으면 429 RATE_LIMITED).
 * 명세에는 숫자가 없어 분석 요청 제한(AnalysisRequestRateLimiter)과 같은 방식으로 둔다. 서버 메모리에만 있으므로
 * 서버가 여러 대면 대마다 따로 센다. 확인에서 실패한 질문(404 등)도 센다.
 */
@Component
public class ChatRateLimiter {

    private static final int WINDOW_SECONDS = 60;
    /** 창 수 상한 (메모리 보호). 차면 가장 오래된 창부터 지워 새 사용자 자리를 만든다 (처음 오는 사용자를 막지 않음) */
    private static final int MAX_WINDOWS = 10_000;

    private final Clock clock;
    private final int limit;
    private final int maxWindows;
    /** 넣은 순서 = 창 시작 순서 (만료된 창은 지우고 다시 넣으므로) */
    private final Map<UUID, Window> windows = new LinkedHashMap<>();

    @Autowired
    public ChatRateLimiter(@Value("${docai.chat.requests-per-minute:10}") int limit) {
        this(Clock.systemUTC(), limit);
    }

    ChatRateLimiter(Clock clock, int limit) {
        this(clock, limit, MAX_WINDOWS);
    }

    ChatRateLimiter(Clock clock, int limit, int maxWindows) {
        this.clock = clock;
        this.limit = limit;
        this.maxWindows = maxWindows;
    }

    public synchronized void check(UUID userId) {
        Instant now = clock.instant();
        windows.entrySet().removeIf(e -> !now.isBefore(e.getValue().start.plusSeconds(WINDOW_SECONDS)));
        Window window = windows.get(userId);
        if (window == null) {
            Iterator<UUID> oldest = windows.keySet().iterator();
            while (windows.size() >= maxWindows && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
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
