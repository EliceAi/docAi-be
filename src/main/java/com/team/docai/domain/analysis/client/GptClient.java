package com.team.docai.domain.analysis.client;

import com.team.docai.global.config.OpenAiProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.core.JsonLocation;
import com.team.docai.domain.analysis.dto.AiCallLogCommand;
import com.team.docai.domain.analysis.entity.AiCallStatus;
import com.team.docai.domain.analysis.service.AiCallLogWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * OpenAI Chat Completions 호출 공통 클라이언트 (구조화 출력).
 *
 * <ul>
 *   <li>트랜잭션을 붙이지 않는다. 응답을 기다리는 동안 DB 연결·행 잠금을 쥐지 않기 위해서다.
 *       호출하는 쪽도 트랜잭션 밖에서 불러야 한다.</li>
 *   <li>429·5xx·시간 초과·네트워크 오류만 지수 백오프로 재시도한다. Retry-After 가 있으면 따른다.</li>
 *   <li>시도마다 ai_call_log 를 별도 트랜잭션(AiCallLogWriter, REQUIRES_NEW)으로 남긴다.
 *       기록 저장이 실패해도 결과 반환은 막지 않는다.</li>
 *   <li>요청 본문(프롬프트·이미지 base64)은 로그·DB에 남기지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class GptClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int ERROR_BODY_SNIPPET = 500;
    /**
     * OpenAI 오류 문구에 섞여 오는 API 키 조각 (예: 401 "Incorrect API key provided: sk-proj-abc...wxyz").
     * 앞이 단어 글자면 키가 아니다 (risk-free, task-force). 끝의 문장 마침표는 키에 넣지 않는다.
     */
    private static final Pattern API_KEY = Pattern.compile(
            "(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_\\-*.]*[A-Za-z0-9_\\-*]");

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final RestClient restClient;
    private final OpenAiProperties properties;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final ObjectMapper strictMapper;
    private final Sleeper sleeper;

    @Autowired
    public GptClient(@Qualifier("openAiRestClient") RestClient openAiRestClient,
                     OpenAiProperties properties,
                     AiCallLogWriter aiCallLogWriter,
                     ObjectMapper objectMapper) {
        this(openAiRestClient, properties, aiCallLogWriter, objectMapper, d -> Thread.sleep(d.toMillis()));
    }

    GptClient(RestClient restClient, OpenAiProperties properties, AiCallLogWriter aiCallLogWriter,
              ObjectMapper objectMapper, Sleeper sleeper) {
        this.restClient = restClient;
        this.properties = properties;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
        // 응답 record 와 어긋나면 스키마 불일치로 본다: 모르는 필드, 빠진 필드, 기본형에 null
        this.strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        this.sleeper = sleeper;
    }

    public <T> T call(GptPurpose purpose, GptRequest request, Class<T> responseType, GptContext ctx) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responseType, "responseType");
        // 트랜잭션 안에서 부르면 응답을 기다리는 동안 DB 연결·행 잠금을 쥔다. 또 REQUIRES_NEW 기록이
        // 커넥션을 하나 더 잡고, 바깥이 잠근 행·미커밋 행을 FK 로 가리키면 기록 유실이나 대기가 생긴다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("GPT 호출은 트랜잭션 밖에서 해야 합니다 (purpose=" + purpose + ").");
        }
        GptContext context = ctx == null ? GptContext.none() : ctx;

        if (!StringUtils.hasText(properties.apiKey())) {
            String detail = "OPENAI_API_KEY 가 설정되지 않았습니다.";
            writeLog(purpose, context, properties.model(), Usage.EMPTY, 0, AiCallStatus.FAILED, detail);
            throw new GptCallException(GptFailureReason.NOT_CONFIGURED, detail, 0);
        }

        String body = buildBody(purpose, request);
        int maxAttempts = Math.max(0, properties.maxRetries()) + 1;

        for (int attempt = 1; ; attempt++) {
            long startedAt = System.nanoTime();
            try {
                Completion completion = send(body);
                T value = parse(completion, responseType);
                writeLog(purpose, context, completion.model(), completion.usage(), elapsedMs(startedAt),
                        AiCallStatus.OK, null);
                return value;
            } catch (AttemptFailure failure) {
                Duration wait = retryWait(failure, attempt, maxAttempts);
                AiCallStatus status = wait != null ? AiCallStatus.RETRY : AiCallStatus.FAILED;
                writeLog(purpose, context, failure.model(), failure.usage(), elapsedMs(startedAt),
                        status, failure.reason() + ": " + failure.detail());
                if (wait == null) {
                    log.warn("GPT 호출 실패: purpose={}, reason={}, attempts={}", purpose, failure.reason(), attempt);
                    throw new GptCallException(failure.reason(), failure.detail(), attempt);
                }
                log.info("GPT 호출 재시도: purpose={}, reason={}, attempt={}, wait={}ms",
                        purpose, failure.reason(), attempt, wait.toMillis());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    String detail = "재시도 대기 중 중단됨";
                    writeLog(purpose, context, failure.model(), Usage.EMPTY, 0, AiCallStatus.FAILED,
                            GptFailureReason.INTERRUPTED + ": " + detail);
                    throw new GptCallException(GptFailureReason.INTERRUPTED, detail, attempt);
                }
            }
        }
    }

    // ---- 요청 ----

    private String buildBody(GptPurpose purpose, GptRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (StringUtils.hasText(request.systemPrompt())) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        for (GptContentPart part : request.userContent()) {
            if (part instanceof GptContentPart.Text text) {
                parts.add(Map.of("type", "text", "text", text.text()));
            } else if (part instanceof GptContentPart.Image image) {
                String dataUrl = "data:" + image.mimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(image.data());
                parts.add(Map.of("type", "image_url",
                        "image_url", Map.of("url", dataUrl, "detail", image.detail().apiValue())));
            }
        }
        messages.add(Map.of("role", "user", "content", parts));

        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", request.schemaName());
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", request.schema());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", messages);
        body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchema));
        // 추론 모델(gpt-5 계열): temperature 를 받지 않고, max_completion_tokens 는 추론 토큰을 포함한다.
        // 요청에 값이 없으면 목적별 설정값(openai.purposes)을 쓴다. 추론 모델이 아니면 예전처럼 요청 값만 보낸다.
        boolean reasoning = properties.reasoningModel();
        OpenAiProperties.Purpose defaults = properties.purpose(purpose.name());
        Integer maxTokens = request.maxOutputTokens() != null ? request.maxOutputTokens()
                : (reasoning ? defaults.maxCompletionTokens() : null);
        if (maxTokens != null) {
            body.put("max_completion_tokens", maxTokens);
        }
        if (reasoning) {
            String effort = request.reasoningEffort() != null ? request.reasoningEffort().apiValue()
                    : defaults.reasoningEffort();
            if (effort != null) {
                body.put("reasoning_effort", effort);
            }
        } else if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            // 요청 본문은 메시지에 담지 않는다
            throw new IllegalArgumentException("GPT 요청 본문을 만들 수 없습니다 (스키마 값 확인).", e);
        }
    }

    private Completion send(String body) {
        HttpResult result;
        try {
            result = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> {
                        InputStream in = res.getBody();
                        String text = in == null ? "" : StreamUtils.copyToString(in, StandardCharsets.UTF_8);
                        return new HttpResult(res.getStatusCode().value(), res.getHeaders(), text);
                    });
        } catch (ResourceAccessException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AttemptFailure(GptFailureReason.INTERRUPTED, "요청 중 중단됨");
            }
            if (isTimeout(e)) {
                throw new AttemptFailure(GptFailureReason.TIMEOUT, "시간 초과: " + e.getMessage());
            }
            throw new AttemptFailure(GptFailureReason.NETWORK_ERROR, "네트워크 오류: " + e.getMessage());
        } catch (RestClientException e) {
            throw new AttemptFailure(GptFailureReason.NETWORK_ERROR, "요청 오류: " + e.getMessage());
        } catch (RuntimeException e) {
            // 요청을 만들거나 보내기 전에 난 오류 (예: 키에 줄바꿈 → JDK 헤더 검사의 IllegalArgumentException).
            // 다시 보내도 같으므로 재시도하지 않는다. 메시지에 헤더 값(키)이 들어갈 수 있어 클래스 이름만 남긴다.
            throw new AttemptFailure(GptFailureReason.CLIENT_ERROR, "요청 생성 실패: " + e.getClass().getSimpleName());
        }

        if (result.status() >= 200 && result.status() < 300) {
            return toCompletion(result.body());
        }
        JsonNode error = errorNodeOf(result.body());
        String detail = "HTTP " + result.status() + ": " + errorMessageOf(error, result.body());
        if (result.status() == 429 && "insufficient_quota".equals(textOrNull(error.path("code")))) {
            // 결제·한도 문제라 기다려도 풀리지 않는다
            throw new AttemptFailure(GptFailureReason.QUOTA_EXCEEDED, detail);
        }
        if (result.status() == 429) {
            throw new AttemptFailure(GptFailureReason.RATE_LIMITED, detail, retryAfterOf(result.headers()));
        }
        if (result.status() >= 500) {
            throw new AttemptFailure(GptFailureReason.SERVER_ERROR, detail, retryAfterOf(result.headers()));
        }
        throw new AttemptFailure(GptFailureReason.CLIENT_ERROR, detail);
    }

    // ---- 응답 ----

    private Completion toCompletion(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new AttemptFailure(GptFailureReason.INVALID_JSON, "응답 본문이 JSON 이 아닙니다.");
        }
        if (root == null || !root.isObject()) {
            throw new AttemptFailure(GptFailureReason.INVALID_JSON, "응답 본문이 JSON 객체가 아닙니다.");
        }
        String model = root.path("model").isTextual() ? root.path("model").asText() : properties.model();
        JsonNode usageNode = root.path("usage");
        Usage usage = new Usage(
                intOrNull(usageNode.path("prompt_tokens")),
                intOrNull(usageNode.path("prompt_tokens_details").path("cached_tokens")),
                intOrNull(usageNode.path("completion_tokens")));
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        return new Completion(model, usage,
                textOrNull(choice.path("finish_reason")),
                textOrNull(message.path("refusal")),
                textOrNull(message.path("content")));
    }

    private <T> T parse(Completion completion, Class<T> responseType) {
        if (completion.refusal() != null) {
            // 거절 문구에 계약서 내용이 섞일 수 있어 원문은 남기지 않는다
            throw completion.failure(GptFailureReason.REFUSAL,
                    "refusal (" + completion.refusal().length() + "자)");
        }
        if ("content_filter".equals(completion.finishReason())) {
            throw completion.failure(GptFailureReason.REFUSAL, "콘텐츠 필터로 중단됨");
        }
        if ("length".equals(completion.finishReason())) {
            // 추론 모델은 추론 토큰도 한도에 들어가 답을 한 글자도 못 쓰고 끝날 수 있다 (content 비어 있음)
            throw completion.failure(GptFailureReason.LENGTH, StringUtils.hasText(completion.content())
                    ? "출력 토큰 한도로 응답이 잘림"
                    : "출력 토큰 한도로 응답이 비어 있음 (추론에 한도를 다 씀)");
        }
        if (!StringUtils.hasText(completion.content())) {
            throw completion.failure(GptFailureReason.INVALID_JSON,
                    "응답 내용이 비어 있음 (finish_reason=" + completion.finishReason() + ")");
        }
        try {
            return strictMapper.readValue(completion.content(), responseType);
        } catch (StreamReadException e) {
            // Jackson 메시지에는 출력 원문 조각이 들어가므로 예외 종류와 위치만 남긴다
            throw completion.failure(GptFailureReason.INVALID_JSON,
                    "응답 JSON 파싱 실패: " + e.getClass().getSimpleName() + locationOf(e.getLocation()));
        } catch (DatabindException e) {
            throw completion.failure(GptFailureReason.SCHEMA_MISMATCH,
                    "응답이 " + responseType.getSimpleName() + " 와 맞지 않음: "
                            + e.getClass().getSimpleName() + " at " + pathOf(e));
        } catch (JsonProcessingException e) {
            throw completion.failure(GptFailureReason.INVALID_JSON,
                    "응답 JSON 처리 실패: " + e.getClass().getSimpleName() + locationOf(e.getLocation()));
        }
    }

    private static String locationOf(JsonLocation location) {
        if (location == null) {
            return "";
        }
        return " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }

    /** 필드 경로만 (예: $.items[2].title). 값은 넣지 않는다. */
    private static String pathOf(DatabindException e) {
        StringBuilder path = new StringBuilder("$");
        if (e instanceof JsonMappingException mapping) {
            for (JsonMappingException.Reference ref : mapping.getPath()) {
                if (ref.getFieldName() != null) {
                    path.append('.').append(ref.getFieldName());
                } else if (ref.getIndex() >= 0) {
                    path.append('[').append(ref.getIndex()).append(']');
                }
            }
        }
        return path.toString();
    }

    // ---- 재시도 ----

    /** 다시 시도하면 대기 시간, 아니면 null. */
    private Duration retryWait(AttemptFailure failure, int attempt, int maxAttempts) {
        if (!failure.reason().isRetryable() || attempt >= maxAttempts) {
            return null;
        }
        if (failure.retryAfter() != null) {
            if (failure.retryAfter().compareTo(properties.maxRetryAfter()) > 0) {
                return null;
            }
            return failure.retryAfter();
        }
        Duration wait = properties.initialBackoff().multipliedBy(1L << Math.min(attempt - 1, 20));
        return wait.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : wait;
    }

    static Duration retryAfterOf(HttpHeaders headers) {
        String millis = headers.getFirst("retry-after-ms");
        if (millis != null) {
            try {
                return Duration.ofMillis(Math.max(0, (long) Double.parseDouble(millis.trim())));
            } catch (NumberFormatException ignored) {
                // Retry-After 로 넘어간다
            }
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            // HTTP 날짜 형식
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration d = Duration.between(ZonedDateTime.now(at.getZone()), at);
            return d.isNegative() ? Duration.ZERO : d;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    // ---- 기록 ----

    private void writeLog(GptPurpose purpose, GptContext ctx, String model, Usage usage, int latencyMs,
                          AiCallStatus status, String errorMessage) {
        // 인터럽트 상태로 커넥션을 얻으면 Hikari 가 실패해 기록이 사라진다. 기록하는 동안만 지우고 다시 켠다.
        boolean interrupted = Thread.interrupted();
        try {
            aiCallLogWriter.write(new AiCallLogCommand(
                    ctx.userId(), purpose, ctx.analysisId(), ctx.chunkId(), ctx.comparisonId(),
                    ctx.conversationId(), model == null ? properties.model() : model,
                    usage.inputTokens(), usage.cachedInputTokens(), usage.outputTokens(),
                    latencyMs, status, errorMessage));
        } catch (RuntimeException e) {
            log.warn("ai_call_log 저장 실패 (GPT 결과 처리는 계속함): purpose={}, status={}, error={}",
                    purpose, status, e.toString());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private JsonNode errorNodeOf(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.missingNode();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root == null ? objectMapper.missingNode() : root.path("error");
        } catch (JsonProcessingException e) {
            return objectMapper.missingNode();
        }
    }

    private static String errorMessageOf(JsonNode error, String body) {
        if (error.path("message").isTextual()) {
            String code = textOrNull(error.path("code"));
            return (code == null ? "" : "[" + code + "] ") + error.path("message").asText();
        }
        if (body == null || body.isBlank()) {
            return "(본문 없음)";
        }
        return body.length() <= ERROR_BODY_SNIPPET ? body : body.substring(0, ERROR_BODY_SNIPPET);
    }

    /**
     * 기록(ai_call_log.error_message)과 예외(detail, toString)에 API 키 조각이 남지 않게 sk-*** 로 바꾼다.
     * 시도 실패 사유는 모두 AttemptFailure 를 거치므로 그 생성자에서 한 번에 처리한다.
     */
    static String maskApiKeys(String text) {
        return text == null ? null : API_KEY.matcher(text).replaceAll("sk-***");
    }

    private static int elapsedMs(long startedAt) {
        long ms = (System.nanoTime() - startedAt) / 1_000_000L;
        return (int) Math.min(ms, Integer.MAX_VALUE);
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    // ---- 내부 값 ----

    private record HttpResult(int status, HttpHeaders headers, String body) {
    }

    private record Usage(Integer inputTokens, Integer cachedInputTokens, Integer outputTokens) {
        static final Usage EMPTY = new Usage(null, null, null);
    }

    private record Completion(String model, Usage usage, String finishReason, String refusal, String content) {
        AttemptFailure failure(GptFailureReason reason, String detail) {
            return new AttemptFailure(reason, detail, null, model, usage);
        }
    }

    /** 시도 1회의 실패. call() 밖으로 나가지 않는다. */
    private static final class AttemptFailure extends RuntimeException {
        private final GptFailureReason reason;
        private final String detail;
        private final Duration retryAfter;
        private final String model;
        private final Usage usage;

        AttemptFailure(GptFailureReason reason, String detail) {
            this(reason, detail, null);
        }

        AttemptFailure(GptFailureReason reason, String detail, Duration retryAfter) {
            this(reason, detail, retryAfter, null, Usage.EMPTY);
        }

        AttemptFailure(GptFailureReason reason, String detail, Duration retryAfter, String model, Usage usage) {
            this(reason, maskApiKeys(detail), retryAfter, model, usage, true);
        }

        private AttemptFailure(GptFailureReason reason, String detail, Duration retryAfter, String model, Usage usage,
                               boolean masked) {
            super(detail, null, false, false);
            this.reason = reason;
            this.detail = detail;
            this.retryAfter = retryAfter;
            this.model = model;
            this.usage = usage;
        }

        GptFailureReason reason() { return reason; }
        String detail() { return detail; }
        Duration retryAfter() { return retryAfter; }
        String model() { return model; }
        Usage usage() { return usage; }
    }
}
