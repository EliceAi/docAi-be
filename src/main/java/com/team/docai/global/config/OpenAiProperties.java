package com.team.docai.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * application.yml 의 openai 구역.
 *
 * @param maxRetries     재시도 횟수 (첫 시도 제외). 429·5xx·시간 초과·네트워크 오류만 재시도
 * @param initialBackoff 첫 재시도 대기. 이후 2배씩 늘고 maxBackoff 를 넘지 않음
 * @param maxRetryAfter  Retry-After 가 이보다 길면 기다리지 않고 실패 처리
 * @param purposes       호출 목적별 기본값 (키: extract, judge, judge-more, compare, chat). 설정에 없는 목적·칸은
 *                       {@link #DEFAULT_PURPOSES} 값을 쓴다. 추론 모델(gpt-5 계열)에만 적용된다.
 */
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        String apiKey,
        @DefaultValue("gpt-5.5") String model,
        @DefaultValue("https://api.openai.com/v1") String baseUrl,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("180s") Duration readTimeout,
        @DefaultValue("1") int maxRetries,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("10s") Duration maxBackoff,
        @DefaultValue("30s") Duration maxRetryAfter,
        Map<String, Purpose> purposes
) {
    public static final String DEFAULT_MODEL = "gpt-5.5";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    /** gpt-5.5 모델 문서의 reasoning_effort 값 (none, low, medium(기본), high, xhigh). minimal 은 gpt-5.5 가 받지 않아 뺐다 */
    public static final Set<String> REASONING_EFFORTS = Set.of("none", "low", "medium", "high", "xhigh");

    /**
     * 목적별 기본값. max_completion_tokens 는 추론 토큰을 포함하므로 출력 JSON 보다 넉넉히 잡는다.
     * EXTRACT: 이미지 5쪽의 모든 조항 원문을 옮겨 적는다 (한 쪽 약 2~3천 토큰 + 추론).
     */
    public static final Map<String, Purpose> DEFAULT_PURPOSES = Map.of(
            "EXTRACT", new Purpose("low", 32000),
            "JUDGE", new Purpose("medium", 16000),
            "JUDGEMORE", new Purpose("medium", 8000),
            "COMPARE", new Purpose("low", 8000),
            "CHAT", new Purpose("low", 6000));

    /**
     * @param reasoningEffort     reasoning_effort (none/low/medium/high/xhigh). null 이면 기본값
     * @param maxCompletionTokens max_completion_tokens (추론 토큰 포함). null 이면 기본값
     */
    public record Purpose(String reasoningEffort, Integer maxCompletionTokens) {
    }

    // .env 에 OPENAI_MODEL= 처럼 빈 값만 있으면 기본값 대신 빈 문자열이 들어오므로 다시 채운다
    @ConstructorBinding
    public OpenAiProperties {
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        Map<String, Purpose> merged = new LinkedHashMap<>(DEFAULT_PURPOSES);
        if (purposes != null) {
            purposes.forEach((key, value) -> {
                String norm = normalizePurpose(key);
                Purpose base = merged.get(norm);
                String effort = value == null || value.reasoningEffort() == null || value.reasoningEffort().isBlank()
                        ? (base == null ? null : base.reasoningEffort())
                        : value.reasoningEffort().trim().toLowerCase(Locale.ROOT);
                Integer tokens = value == null || value.maxCompletionTokens() == null
                        ? (base == null ? null : base.maxCompletionTokens()) : value.maxCompletionTokens();
                merged.put(norm, new Purpose(effort, tokens));
            });
        }
        merged.forEach((key, value) -> {
            if (value.reasoningEffort() != null && !REASONING_EFFORTS.contains(value.reasoningEffort())) {
                throw new IllegalArgumentException("openai.purposes." + key + ".reasoning-effort 값이 올바르지 않습니다: "
                        + value.reasoningEffort());
            }
            if (value.maxCompletionTokens() != null && value.maxCompletionTokens() < 1) {
                throw new IllegalArgumentException("openai.purposes." + key + ".max-completion-tokens 는 1 이상이어야 합니다.");
            }
        });
        purposes = Map.copyOf(merged);
    }

    /** 목적별 설정 없이 만든다 (목적별 값은 기본값). 테스트·기존 호출용 */
    public OpenAiProperties(String apiKey, String model, String baseUrl, Duration connectTimeout, Duration readTimeout,
                            int maxRetries, Duration initialBackoff, Duration maxBackoff, Duration maxRetryAfter) {
        this(apiKey, model, baseUrl, connectTimeout, readTimeout, maxRetries, initialBackoff, maxBackoff, maxRetryAfter,
                null);
    }

    /** 목적(GptPurpose 이름) 의 설정. 없으면 둘 다 null */
    public Purpose purpose(String purposeName) {
        Purpose p = purposes.get(normalizePurpose(purposeName));
        return p == null ? new Purpose(null, null) : p;
    }

    /** 추론 모델(gpt-5 계열, o 계열)인지. 이 모델들은 temperature 를 받지 않고 reasoning_effort 를 받는다. */
    public boolean reasoningModel() {
        String m = model.toLowerCase(Locale.ROOT);
        return m.startsWith("gpt-5") || m.matches("^o\\d.*");
    }

    private static String normalizePurpose(String key) {
        return key == null ? "" : key.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }
}
