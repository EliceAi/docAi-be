package com.team.docai.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * application.yml 의 openai 구역.
 *
 * @param maxRetries    재시도 횟수 (첫 시도 제외). 429·5xx·시간 초과·네트워크 오류만 재시도
 * @param initialBackoff 첫 재시도 대기. 이후 2배씩 늘고 maxBackoff 를 넘지 않음
 * @param maxRetryAfter Retry-After 가 이보다 길면 기다리지 않고 실패 처리
 */
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        String apiKey,
        @DefaultValue("gpt-4o-2024-08-06") String model,
        @DefaultValue("https://api.openai.com/v1") String baseUrl,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("120s") Duration readTimeout,
        @DefaultValue("1") int maxRetries,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("10s") Duration maxBackoff,
        @DefaultValue("30s") Duration maxRetryAfter
) {
    public static final String DEFAULT_MODEL = "gpt-4o-2024-08-06";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    // .env 에 OPENAI_MODEL= 처럼 빈 값만 있으면 기본값 대신 빈 문자열이 들어오므로 다시 채운다
    public OpenAiProperties {
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }
}
