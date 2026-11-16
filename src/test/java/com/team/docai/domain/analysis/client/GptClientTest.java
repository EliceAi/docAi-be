package com.team.docai.domain.analysis.client;

import com.team.docai.global.config.OpenAiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.dto.AiCallLogCommand;
import com.team.docai.domain.analysis.entity.AiCallStatus;
import com.team.docai.domain.analysis.service.AiCallLogWriter;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GptClientTest {

    static final String BASE_URL = "https://openai.test/v1";
    static final String URL = BASE_URL + "/chat/completions";
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("title", Map.of("type", "string", "maxLength", 100),
                    "count", Map.of("type", "integer")),
            "required", List.of("title", "count"),
            "additionalProperties", false);

    public record Sample(String title, int count) {
    }

    final ObjectMapper objectMapper = new ObjectMapper();
    final List<Duration> sleeps = new ArrayList<>();
    MockRestServiceServer server;
    AiCallLogWriter writer;
    GptClient client;

    @BeforeEach
    void setUp() {
        sleeps.clear();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        writer = mock(AiCallLogWriter.class);
        // 재시도 1회 (담당_연결지점 A8, application.yml 기본값과 같음)
        OpenAiProperties properties = new OpenAiProperties("test-key", "gpt-4o-2024-08-06", BASE_URL,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(30));
        client = new GptClient(builder.build(), properties, writer, objectMapper, sleeps::add);
    }

    GptRequest textRequest() {
        return GptRequest.of("system", List.of(GptContentPart.text("조항을 판단해 줘")), "sample", SCHEMA);
    }

    String completion(String content, String finishReason, String refusal) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        message.put("refusal", refusal);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        return objectMapper.writeValueAsString(Map.of(
                "id", "chatcmpl-1",
                "model", "gpt-4o-2024-08-06",
                "choices", List.of(choice),
                "usage", Map.of("prompt_tokens", 120, "completion_tokens", 30,
                        "prompt_tokens_details", Map.of("cached_tokens", 64))));
    }

    String ok() throws Exception {
        return completion("{\"title\":\"근로계약서\",\"count\":3}", "stop", null);
    }

    List<AiCallLogCommand> logs(int expected) {
        ArgumentCaptor<AiCallLogCommand> captor = ArgumentCaptor.forClass(AiCallLogCommand.class);
        verify(writer, times(expected)).write(captor.capture());
        return captor.getAllValues();
    }

    GptCallException callExpectingFailure() {
        try {
            client.call(GptPurpose.JUDGE, textRequest(), Sample.class, GptContext.none());
        } catch (GptCallException e) {
            return e;
        }
        throw new AssertionError("GptCallException 이 나와야 함");
    }

    @Test
    void 정상_응답을_record로_역직렬화하고_구조화_출력을_요청한다() throws Exception {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("gpt-4o-2024-08-06"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("sample"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.title.maxLength").value(100))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content[0].text").value("조항을 판단해 줘"))
                .andRespond(withSuccess(ok(), MediaType.APPLICATION_JSON));

        UUID userId = UUID.randomUUID();
        Sample result = client.call(GptPurpose.JUDGE, textRequest(), Sample.class, GptContext.chunk(userId, 7L, 8L));

        assertThat(result).isEqualTo(new Sample("근로계약서", 3));
        server.verify();
        AiCallLogCommand log = logs(1).get(0);
        assertThat(log.status()).isEqualTo(AiCallStatus.OK);
        assertThat(log.purpose()).isEqualTo(GptPurpose.JUDGE);
        assertThat(log.userId()).isEqualTo(userId);
        assertThat(log.analysisId()).isEqualTo(7L);
        assertThat(log.chunkId()).isEqualTo(8L);
        assertThat(log.model()).isEqualTo("gpt-4o-2024-08-06");
        assertThat(log.inputTokens()).isEqualTo(120);
        assertThat(log.cachedInputTokens()).isEqualTo(64);
        assertThat(log.outputTokens()).isEqualTo(30);
        assertThat(log.latencyMs()).isNotNull();
        assertThat(log.errorMessage()).isNull();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 이미지는_base64_data_URL_image_url_파트로_글자와_함께_들어간다() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content", hasSize(2)))
                .andExpect(jsonPath("$.messages[0].content[0].type").value("text"))
                .andExpect(jsonPath("$.messages[0].content[0].text").value("1쪽"))
                .andExpect(jsonPath("$.messages[0].content[1].type").value("image_url"))
                .andExpect(jsonPath("$.messages[0].content[1].image_url.url", equalTo(dataUrl)))
                .andExpect(jsonPath("$.messages[0].content[1].image_url.detail").value("high"))
                .andRespond(withSuccess(ok(), MediaType.APPLICATION_JSON));

        GptRequest request = GptRequest.of(null,
                List.of(GptContentPart.text("1쪽"), GptContentPart.image(png, "image/png")), "sample", SCHEMA);
        client.call(GptPurpose.EXTRACT, request, Sample.class, GptContext.none());

        server.verify();
        // 기록에는 요청 본문(이미지)이 들어가지 않는다
        assertThat(logs(1).get(0).toString()).doesNotContain(Base64.getEncoder().encodeToString(png));
    }

    @Test
    void 요청_429_뒤_Retry_After만큼_기다려_재시도하고_성공한다() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "3");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"Rate limit reached\"}}"));
        server.expect(requestTo(URL)).andRespond(withSuccess(ok(), MediaType.APPLICATION_JSON));

        Sample result = client.call(GptPurpose.JUDGE, textRequest(), Sample.class, GptContext.none());

        assertThat(result.count()).isEqualTo(3);
        server.verify();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
        List<AiCallLogCommand> logs = logs(2);
        assertThat(logs).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.RETRY, AiCallStatus.OK);
        assertThat(logs.get(0).errorMessage()).contains("RATE_LIMITED").contains("429").contains("Rate limit reached");
    }

    @Test
    void 시간_초과는_지수_백오프로_재시도한다() throws Exception {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(requestTo(URL)).andRespond(withSuccess(ok(), MediaType.APPLICATION_JSON));

        client.call(GptPurpose.JUDGE, textRequest(), Sample.class, GptContext.none());

        server.verify();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        assertThat(logs(2)).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.RETRY, AiCallStatus.OK);
    }

    @Test
    void 요청_400은_재시도하지_않는다() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"Invalid schema\"}}"));

        GptCallException e = callExpectingFailure();

        server.verify();
        assertThat(e.getReason()).isEqualTo(GptFailureReason.CLIENT_ERROR);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_REQUEST_FAILED);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getMessage()).isEqualTo(ErrorCode.AI_REQUEST_FAILED.getMessage());
        assertThat(sleeps).isEmpty();
        assertThat(logs(1)).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.FAILED);
    }

    @Test
    void 오류_문구의_API_키_조각은_기록과_예외에_남지_않는다() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"Incorrect API key provided: sk-proj-abc...wxyz. You can find your API key"
                        + " at https://platform.openai.com/account/api-keys.\",\"code\":\"invalid_api_key\"}}"));

        GptCallException e = callExpectingFailure();

        server.verify();
        assertThat(e.getReason()).isEqualTo(GptFailureReason.CLIENT_ERROR);
        String logged = logs(1).get(0).errorMessage();
        for (String text : List.of(e.getDetail(), e.toString(), e.getMessage(), logged)) {
            assertThat(text).doesNotContain("sk-proj").doesNotContain("wxyz").doesNotContain("abc...");
        }
        assertThat(logged).contains("sk-***").contains("401").contains("invalid_api_key");
        assertThat(GptClient.maskApiKeys("key sk-abcd1234 and sk-proj-AB_cd-*** end")).isEqualTo("key sk-*** and sk-*** end");
    }

    @Test
    void 키_가리기는_일반_단어와_문장_마침표를_건드리지_않는다() {
        // 일반 단어 보존
        assertThat(GptClient.maskApiKeys("risk-free task-force desk-top ask-me")).isEqualTo("risk-free task-force desk-top ask-me");
        // 끝 마침표 보존
        assertThat(GptClient.maskApiKeys("provided: sk-proj-abc...wxyz. You can")).isEqualTo("provided: sk-***. You can");
        assertThat(GptClient.maskApiKeys("key=sk-abcd1234.")).isEqualTo("key=sk-***.");
        // 기존 키 형태는 계속 가려짐
        assertThat(GptClient.maskApiKeys("sk-abcd1234")).isEqualTo("sk-***");
        assertThat(GptClient.maskApiKeys("Bearer sk-proj-AB_cd-12*34xyz")).isEqualTo("Bearer sk-***");
        assertThat(GptClient.maskApiKeys("(sk-svcacct-Zz9...Yy8)")).isEqualTo("(sk-***)");
        assertThat(GptClient.maskApiKeys("\"sk-ABC\"")).isEqualTo("\"sk-***\"");
        assertThat(GptClient.maskApiKeys(null)).isNull();
    }

    @Test
    void 요청을_보내기_전_오류는_재시도_없이_CLIENT_ERROR로_기록하고_메시지는_버린다() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(request -> {
            throw new IllegalArgumentException("invalid header value: \"Bearer sk-secret\\nX\"");
        });

        GptCallException e = callExpectingFailure();

        server.verify();
        assertThat(e.getReason()).isEqualTo(GptFailureReason.CLIENT_ERROR);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_REQUEST_FAILED);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getDetail()).isEqualTo("요청 생성 실패: IllegalArgumentException");
        assertThat(sleeps).isEmpty();
        AiCallLogCommand log = logs(1).get(0);
        assertThat(log.status()).isEqualTo(AiCallStatus.FAILED);
        assertThat(log.errorMessage()).isEqualTo("CLIENT_ERROR: 요청 생성 실패: IllegalArgumentException");
        for (String text : List.of(e.getDetail(), e.toString(), log.errorMessage())) {
            assertThat(text).doesNotContain("secret").doesNotContain("Bearer");
        }
    }

    @Test
    void 키에_줄바꿈이_있으면_실제_JDK_클라이언트도_CLIENT_ERROR로_끝난다() {
        // 헤더 검사는 연결 전에 일어나므로 서버가 없어도 된다 (닫힌 포트)
        OpenAiProperties properties = new OpenAiProperties("test-\nkey", "gpt-4o-2024-08-06", "http://127.0.0.1:9/v1",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(30));
        GptClient badKey = new GptClient(new com.team.docai.global.config.OpenAiClientConfig().openAiRestClient(properties),
                properties, writer, objectMapper, sleeps::add);

        assertThatThrownBy(() -> badKey.call(GptPurpose.JUDGE, textRequest(), Sample.class, GptContext.none()))
                .isInstanceOf(GptCallException.class)
                .satisfies(t -> {
                    GptCallException e = (GptCallException) t;
                    assertThat(e.getReason()).isEqualTo(GptFailureReason.CLIENT_ERROR);
                    assertThat(e.getAttempts()).isEqualTo(1);
                    assertThat(e.getDetail()).startsWith("요청 생성 실패: ").doesNotContain("test-");
                });
        assertThat(sleeps).isEmpty();
        assertThat(logs(1)).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.FAILED);
    }

    @Test
    void 요청_401과_403도_재시도하지_않는다() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)) {
            setUp();
            server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(status));
            assertThat(callExpectingFailure().getReason()).isEqualTo(GptFailureReason.CLIENT_ERROR);
            server.verify();
        }
    }

    @Test
    void 통신_오류는_1회만_재시도하고_마지막_사유로_실패한다() {
        server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withServerError());

        GptCallException e = callExpectingFailure();

        server.verify();
        assertThat(e.getReason()).isEqualTo(GptFailureReason.SERVER_ERROR);
        assertThat(e.getAttempts()).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        assertThat(logs(2)).extracting(AiCallLogCommand::status)
                .containsExactly(AiCallStatus.RETRY, AiCallStatus.FAILED);
    }

    @Test
    void 재시도_횟수를_늘리면_대기는_지수로_늘어난다() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer three = MockRestServiceServer.bindTo(builder).build();
        OpenAiProperties properties = new OpenAiProperties("test-key", "gpt-4o-2024-08-06", BASE_URL,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 2,
                Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(30));
        GptClient twice = new GptClient(builder.build(), properties, writer, objectMapper, sleeps::add);
        three.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> twice.call(GptPurpose.JUDGE, textRequest(), Sample.class, GptContext.none()))
                .isInstanceOf(GptCallException.class);

        three.verify();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(logs(3)).extracting(AiCallLogCommand::status)
                .containsExactly(AiCallStatus.RETRY, AiCallStatus.RETRY, AiCallStatus.FAILED);
    }

    @Test
    void 설정이_없으면_재시도_기본값은_1회() {
        OpenAiProperties bound = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        Map.of("openai.api-key", "k")))
                .bind("openai", OpenAiProperties.class).get();

        assertThat(bound.maxRetries()).isEqualTo(1);
    }

    @Test
    void 시간_초과로_끝나면_AI_TIMEOUT() {
        server.expect(ExpectedCount.times(2), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        GptCallException e = callExpectingFailure();

        assertThat(e.getReason()).isEqualTo(GptFailureReason.TIMEOUT);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
    }

    @Test
    void Retry_After가_상한보다_길면_기다리지_않고_실패한다() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "120");
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        GptCallException e = callExpectingFailure();

        server.verify();
        assertThat(e.getReason()).isEqualTo(GptFailureReason.RATE_LIMITED);
        assertThat(sleeps).isEmpty();
        assertThat(logs(1)).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.FAILED);
    }

    @Test
    void 네트워크_오류도_GPT_오류로_보고_재시도한다() throws Exception {
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("refused")));
        server.expect(requestTo(URL)).andRespond(withSuccess(ok(), MediaType.APPLICATION_JSON));

        client.call(GptPurpose.JUDGE, textRequest(), Sample.class, GptContext.none());

        server.verify();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        assertThat(logs(2)).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.RETRY, AiCallStatus.OK);
    }

    @Test
    void 네트워크_오류로_끝나면_AI_REQUEST_FAILED() {
        server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withException(new ConnectException("refused")));

        GptCallException e = callExpectingFailure();

        server.verify();
        assertThat(e.getReason()).isEqualTo(GptFailureReason.NETWORK_ERROR);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_REQUEST_FAILED);
        assertThat(e.getAttempts()).isEqualTo(2);
    }

    @Test
    void 실제_HTTP_클라이언트의_읽기_시간_초과는_TIMEOUT으로_분류된다() throws Exception {
        // 설정 빈(OpenAiClientConfig)이 만든 JDK HTTP 클라이언트로 실제 소켓을 써서, 읽기 시간 제한이 걸리고 TIMEOUT 으로 잡히는지 본다
        com.sun.net.httpserver.HttpServer slow = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        slow.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        slow.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        slow.start();
        try {
            String baseUrl = "http://127.0.0.1:" + slow.getAddress().getPort() + "/v1";
            OpenAiProperties properties = new OpenAiProperties("test-key", "gpt-4o-2024-08-06", baseUrl,
                    Duration.ofSeconds(1), Duration.ofMillis(300), 1,
                    Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofSeconds(1));
            RestClient real = new com.team.docai.global.config.OpenAiClientConfig().openAiRestClient(properties);
            GptClient timed = new GptClient(real, properties, writer, objectMapper, sleeps::add);

            long started = System.nanoTime();
            GptCallException e = null;
            try {
                timed.call(GptPurpose.CHAT, textRequest(), Sample.class, GptContext.none());
            } catch (GptCallException caught) {
                e = caught;
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

            assertThat(e).isNotNull();
            assertThat(e.getReason()).isEqualTo(GptFailureReason.TIMEOUT);
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
            assertThat(e.getAttempts()).isEqualTo(2);
            assertThat(hits.get()).isEqualTo(2);
            assertThat(elapsedMs).isLessThan(4000);
            assertThat(logs(2)).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.RETRY, AiCallStatus.FAILED);
        } finally {
            release.countDown();
            slow.stop(0);
        }
    }

    @Test
    void 거절_응답은_REFUSAL로_재시도_없이_실패하고_토큰은_기록한다() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(completion(null, "stop", "I can't help with that."), MediaType.APPLICATION_JSON));

        GptCallException e = callExpectingFailure();

        server.verify();
        assertThat(e.getReason()).isEqualTo(GptFailureReason.REFUSAL);
        AiCallLogCommand log = logs(1).get(0);
        assertThat(log.status()).isEqualTo(AiCallStatus.FAILED);
        assertThat(log.inputTokens()).isEqualTo(120);
        assertThat(log.errorMessage()).startsWith("REFUSAL");
    }

    @Test
    void finish_reason_length는_LENGTH() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(completion("{\"title\":\"근로", "length", null), MediaType.APPLICATION_JSON));

        assertThat(callExpectingFailure().getReason()).isEqualTo(GptFailureReason.LENGTH);
        server.verify();
    }

    @Test
    void 깨진_JSON은_INVALID_JSON() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(completion("{\"title\": 근로계약서", "stop", null), MediaType.APPLICATION_JSON));

        assertThat(callExpectingFailure().getReason()).isEqualTo(GptFailureReason.INVALID_JSON);
        server.verify();
    }

    @Test
    void 빈_내용은_INVALID_JSON() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(completion(null, "stop", null), MediaType.APPLICATION_JSON));

        assertThat(callExpectingFailure().getReason()).isEqualTo(GptFailureReason.INVALID_JSON);
    }

    @Test
    void 필드_누락_모르는_필드_타입_불일치는_SCHEMA_MISMATCH() throws Exception {
        for (String content : List.of(
                "{\"title\":\"근로계약서\"}",
                "{\"title\":\"근로계약서\",\"count\":3,\"extra\":1}",
                "{\"title\":\"근로계약서\",\"count\":\"many\"}",
                "{\"title\":\"근로계약서\",\"count\":null}")) {
            setUp();
            server.expect(requestTo(URL)).andRespond(withSuccess(completion(content, "stop", null), MediaType.APPLICATION_JSON));
            assertThat(callExpectingFailure().getReason()).as(content).isEqualTo(GptFailureReason.SCHEMA_MISMATCH);
        }
    }

    @Test
    void 기록_저장이_실패해도_결과는_반환한다() throws Exception {
        doThrow(new RuntimeException("db down")).when(writer).write(any());
        server.expect(requestTo(URL)).andRespond(withSuccess(ok(), MediaType.APPLICATION_JSON));

        Sample result = client.call(GptPurpose.COMPARE, textRequest(), Sample.class, GptContext.comparison(null, 5L));

        assertThat(result.title()).isEqualTo("근로계약서");
    }

    @Test
    void API_키가_없으면_호출하지_않고_실패한다() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer noCall = MockRestServiceServer.bindTo(builder).build();
        OpenAiProperties empty = new OpenAiProperties("", "", BASE_URL, Duration.ofSeconds(1), Duration.ofSeconds(1),
                1, Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(30));
        GptClient noKey = new GptClient(builder.build(), empty, writer, objectMapper, sleeps::add);

        assertThatThrownBy(() -> noKey.call(GptPurpose.JUDGE, textRequest(), Sample.class, null))
                .isInstanceOf(GptCallException.class)
                .extracting(e -> ((GptCallException) e).getReason())
                .isEqualTo(GptFailureReason.NOT_CONFIGURED);
        noCall.verify();
        assertThat(logs(1).get(0).model()).isEqualTo(OpenAiProperties.DEFAULT_MODEL);
    }

    @Test
    void Retry_After_해석() {
        HttpHeaders seconds = new HttpHeaders();
        seconds.set(HttpHeaders.RETRY_AFTER, "5");
        assertThat(GptClient.retryAfterOf(seconds)).isEqualTo(Duration.ofSeconds(5));
        HttpHeaders millis = new HttpHeaders();
        millis.set("retry-after-ms", "250");
        assertThat(GptClient.retryAfterOf(millis)).isEqualTo(Duration.ofMillis(250));
        HttpHeaders date = new HttpHeaders();
        date.set(HttpHeaders.RETRY_AFTER, "Wed, 21 Oct 2015 07:28:00 GMT");
        assertThat(GptClient.retryAfterOf(date)).isEqualTo(Duration.ZERO);
        assertThat(GptClient.retryAfterOf(new HttpHeaders())).isNull();
    }

    @Test
    void 트랜잭션_안에서_부르면_요청을_보내지_않고_막는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> client.call(GptPurpose.JUDGE, textRequest(), Sample.class, GptContext.none()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("트랜잭션 밖");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        server.verify();
        verify(writer, never()).write(any());
    }

    @Test
    void 요청_중_인터럽트는_INTERRUPTED로_재시도_없이_끝난다() {
        List<Boolean> interruptedWhileWriting = new ArrayList<>();
        doAnswer(inv -> interruptedWhileWriting.add(Thread.currentThread().isInterrupted())).when(writer).write(any());
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(request -> {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Request was interrupted");
        });
        try {
            GptCallException e = callExpectingFailure();
            assertThat(e.getReason()).isEqualTo(GptFailureReason.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        server.verify();
        assertThat(sleeps).isEmpty();
        assertThat(logs(1)).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.FAILED);
        // 기록하는 동안은 인터럽트 상태가 꺼져 있어야 커넥션을 얻을 수 있다
        assertThat(interruptedWhileWriting).containsExactly(false);
    }

    @Test
    void 재시도_대기_중_인터럽트되면_FAILED를_한_번_더_남긴다() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer once = MockRestServiceServer.bindTo(builder).build();
        OpenAiProperties properties = new OpenAiProperties("test-key", "gpt-4o-2024-08-06", BASE_URL,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(30));
        GptClient interrupting = new GptClient(builder.build(), properties, writer, objectMapper,
                d -> { throw new InterruptedException(); });
        once.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        List<Boolean> interruptedWhileWriting = new ArrayList<>();
        doAnswer(inv -> interruptedWhileWriting.add(Thread.currentThread().isInterrupted())).when(writer).write(any());

        try {
            assertThatThrownBy(() -> interrupting.call(GptPurpose.JUDGE, textRequest(), Sample.class, GptContext.none()))
                    .isInstanceOf(GptCallException.class)
                    .extracting(e -> ((GptCallException) e).getReason())
                    .isEqualTo(GptFailureReason.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        once.verify();
        List<AiCallLogCommand> logs = logs(2);
        assertThat(logs).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.RETRY, AiCallStatus.FAILED);
        assertThat(logs.get(1).errorMessage()).startsWith("INTERRUPTED");
        assertThat(interruptedWhileWriting).containsExactly(false, false);
    }

    @Test
    void 요청_429_insufficient_quota는_재시도하지_않는다() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}"));

        GptCallException e = callExpectingFailure();

        server.verify();
        assertThat(e.getReason()).isEqualTo(GptFailureReason.QUOTA_EXCEEDED);
        assertThat(sleeps).isEmpty();
        assertThat(logs(1)).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.FAILED);
    }

    @Test
    void 서버_오류_5xx도_Retry_After를_따른다() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "7");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).headers(headers));
        server.expect(requestTo(URL)).andRespond(withSuccess(ok(), MediaType.APPLICATION_JSON));

        client.call(GptPurpose.JUDGE, textRequest(), Sample.class, GptContext.none());

        server.verify();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void finish_reason_content_filter는_REFUSAL() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(completion(null, "content_filter", null), MediaType.APPLICATION_JSON));

        assertThat(callExpectingFailure().getReason()).isEqualTo(GptFailureReason.REFUSAL);
        server.verify();
    }

    @Test
    void 실패_기록과_예외에_모델_출력_원문이_남지_않는다() throws Exception {
        String secret = "임차인홍길동보증금오억";
        Map<String, GptFailureReason> cases = new LinkedHashMap<>();
        cases.put("REFUSAL:" + secret + " 내용은 도와드릴 수 없습니다", GptFailureReason.REFUSAL);
        cases.put("{\"title\": " + secret + "}", GptFailureReason.INVALID_JSON);
        cases.put("{\"title\":\"" + secret + "\",\"count\":\"" + secret + "\"}", GptFailureReason.SCHEMA_MISMATCH);
        cases.put("{\"title\":\"" + secret + "\",\"count\":1,\"extra\":\"" + secret + "\"}", GptFailureReason.SCHEMA_MISMATCH);

        for (Map.Entry<String, GptFailureReason> c : cases.entrySet()) {
            setUp();
            String body = c.getKey().startsWith("REFUSAL:")
                    ? completion(null, "stop", c.getKey().substring("REFUSAL:".length()))
                    : completion(c.getKey(), "stop", null);
            server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            GptCallException e = callExpectingFailure();

            assertThat(e.getReason()).as(c.getKey()).isEqualTo(c.getValue());
            assertThat(e.getDetail()).as(c.getKey()).doesNotContain(secret);
            assertThat(e.toString()).doesNotContain(secret);
            assertThat(logs(1).get(0).errorMessage()).as(c.getKey()).doesNotContain(secret);
        }
    }

    @Test
    void 스키마_불일치_기록에는_필드_경로가_남는다() throws Exception {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                completion("{\"title\":\"x\",\"count\":\"many\"}", "stop", null), MediaType.APPLICATION_JSON));

        GptCallException e = callExpectingFailure();

        assertThat(e.getDetail()).contains("$.count").contains("InvalidFormatException").doesNotContain("many");
    }
}
