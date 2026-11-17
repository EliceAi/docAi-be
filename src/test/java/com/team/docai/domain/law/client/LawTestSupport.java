package com.team.docai.domain.law.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.global.config.LawConfig;
import com.team.docai.global.config.LawProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class LawTestSupport {

    /** 테스트용 가짜 인증값. 실제 값이 아니다. */
    public static final String FAKE_OC = "fake-oc-value-1234";

    private LawTestSupport() {
    }

    public static LawProperties properties(String baseUrl, Duration minInterval, Duration retryDelay) {
        return new LawProperties(false, false, false, false, baseUrl, minInterval, retryDelay,
                Duration.ofSeconds(2), Duration.ofSeconds(5), 100, 3, 3, "", 5, 20, 0.0, 10, 500);
    }

    public static LawProperties properties(String baseUrl, Duration minInterval, Duration retryDelay,
                                           int maxConsecutiveFailures, boolean force) {
        return new LawProperties(false, false, force, false, baseUrl, minInterval, retryDelay,
                Duration.ofSeconds(2), Duration.ofSeconds(5), 100, 3, maxConsecutiveFailures, "", 5, 20, 0.0, 10, 500);
    }

    public static LawApiClient client(LawProperties properties, String oc) {
        return new LawApiClient(new LawConfig().lawApiRestClient(properties), new ObjectMapper(), properties, oc);
    }

    public static String resource(String name) {
        try (InputStream in = LawTestSupport.class.getResourceAsStream("/law/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("테스트 리소스 없음: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
