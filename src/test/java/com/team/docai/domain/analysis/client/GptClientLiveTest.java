package com.team.docai.domain.analysis.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.dto.AiCallLogCommand;
import com.team.docai.domain.analysis.entity.AiCallStatus;
import com.team.docai.domain.analysis.service.AiCallLogWriter;
import com.team.docai.global.config.OpenAiClientConfig;
import com.team.docai.global.config.OpenAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 OpenAI 호출 확인용. 평소에는 건너뛴다 (DOCAI_OPENAI_LIVE_TEST=true 일 때만).
 * DB 없이 GptClient 를 직접 만들고, 기록은 메모리에 모은다. API 키는 환경 변수 또는 .env 에서 읽고 출력하지 않는다.
 * 모델·재시도·시간 제한은 설정 기본값 (OpenAiProperties).
 */
@EnabledIfEnvironmentVariable(named = "DOCAI_OPENAI_LIVE_TEST", matches = "true")
class GptClientLiveTest {

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string"),
                    "count", Map.of("type", "integer")),
            "required", List.of("summary", "count"),
            "additionalProperties", false);

    static final Map<String, Object> SCHEMA_MAX_LENGTH = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string", "maxLength", 20),
                    "count", Map.of("type", "integer")),
            "required", List.of("summary", "count"),
            "additionalProperties", false);

    static final Map<String, Object> IMAGE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("text", Map.of("type", "string")),
            "required", List.of("text"),
            "additionalProperties", false);

    public record Summary(String summary, int count) {
    }

    public record ImageText(String text) {
    }

    /** 기록을 DB 대신 메모리에 모으는 가짜 */
    static final class MemoryLogWriter extends AiCallLogWriter {
        final List<AiCallLogCommand> logs = new ArrayList<>();

        MemoryLogWriter() {
            super(null);
        }

        @Override
        public void write(AiCallLogCommand command) {
            logs.add(command);
        }
    }

    MemoryLogWriter writer;
    GptClient client;
    OpenAiProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = readDotEnv("OPENAI_API_KEY");
        }
        assertThat(apiKey).as("OPENAI_API_KEY 가 환경 변수나 .env 에 있어야 함").isNotBlank();
        // 키 외에는 모두 설정 기본값
        properties = new Binder(new MapConfigurationPropertySource(Map.of("openai.api-key", apiKey)))
                .bind("openai", OpenAiProperties.class).get();
        writer = new MemoryLogWriter();
        client = GptClientTestSupport.create(new OpenAiClientConfig().openAiRestClient(properties),
                properties, writer, new ObjectMapper());
    }

    /** .env 의 NAME=value 한 줄을 읽는다. export 접두어, 따옴표, 줄 끝 "# 주석"을 처리한다. */
    static String readDotEnv(String name) throws IOException {
        Path env = Path.of(".env");
        if (!Files.exists(env)) {
            return null;
        }
        for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("export ")) {
                trimmed = trimmed.substring("export ".length()).trim();
            }
            if (!trimmed.startsWith(name + "=")) {
                continue;
            }
            String value = trimmed.substring(name.length() + 1).trim();
            if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
                int end = value.indexOf(value.charAt(0), 1);
                return end > 0 ? value.substring(1, end) : value.substring(1);
            }
            int comment = value.indexOf(" #");
            if (comment >= 0) {
                value = value.substring(0, comment);
            }
            return value.trim();
        }
        return null;
    }

    static void report(String caseName, List<AiCallLogCommand> logs) {
        int input = logs.stream().map(AiCallLogCommand::inputTokens).filter(Objects::nonNull).mapToInt(i -> i).sum();
        int cached = logs.stream().map(AiCallLogCommand::cachedInputTokens).filter(Objects::nonNull).mapToInt(i -> i).sum();
        int output = logs.stream().map(AiCallLogCommand::outputTokens).filter(Objects::nonNull).mapToInt(i -> i).sum();
        System.out.println("[LIVE] " + caseName + " logs=" + logs.stream().map(l -> l.status() + "/" + l.model()
                + "/" + l.latencyMs() + "ms").toList()
                + " tokens(input=" + input + ", cached=" + cached + ", output=" + output + ")");
    }

    /** 실패하면 사유만 남기고 테스트를 실패시킨다 (detail·toString 의 OpenAI 오류 원문은 출력하지 않음). */
    <T> T callOrFail(GptPurpose purpose, GptRequest request, Class<T> type) {
        try {
            return client.call(purpose, request, type, GptContext.none());
        } catch (GptCallException e) {
            System.out.println("[LIVE] FAILED: reason=" + e.getReason());
            throw new AssertionError("GPT 호출 실패: reason=" + e.getReason(), null);
        }
    }

    GptRequest textRequest(Map<String, Object> schema) {
        return GptRequest.of(
                "너는 문서 요약기다. 주어진 문장을 한국어로 짧게 요약하고, 문장 속 과일 이름 개수를 센다.",
                List.of(GptContentPart.text("사과 두 개와 배 한 개, 그리고 귤 세 개를 샀다. 모두 시장에서 샀다.")),
                "live_summary", schema);
    }

    @Test
    void 케이스1_짧은_텍스트와_작은_스키마() {
        Summary result = callOrFail(GptPurpose.CHAT, textRequest(SCHEMA), Summary.class);

        System.out.println("[LIVE] case1 result summary(" + result.summary().length() + "자)=" + result.summary()
                + ", count=" + result.count());
        report("case1", writer.logs);
        assertThat(result.summary()).isNotBlank();
        assertThat(writer.logs).hasSize(1);
        AiCallLogCommand log = writer.logs.get(0);
        assertThat(log.status()).isEqualTo(AiCallStatus.OK);
        assertThat(log.inputTokens()).isPositive();
        assertThat(log.outputTokens()).isPositive();
        assertThat(log.model()).isNotBlank();
        assertThat(log.latencyMs()).isNotNull();
    }

    @Test
    void 케이스2_maxLength_를_넣은_스키마_확인용() {
        // 성공·거절 어느 쪽이든 실패시키지 않는다. 결과만 출력한다.
        try {
            Summary result = client.call(GptPurpose.CHAT, textRequest(SCHEMA_MAX_LENGTH), Summary.class,
                    GptContext.none());
            System.out.println("[LIVE] case2 ACCEPTED: summary length=" + result.summary().length()
                    + " (<= 20: " + (result.summary().length() <= 20) + "), summary=" + result.summary()
                    + ", count=" + result.count());
        } catch (GptCallException e) {
            // detail 에는 OpenAI 오류 원문이 들어가므로 출력하지 않고 사유만 남긴다
            System.out.println("[LIVE] case2 REJECTED: reason=" + e.getReason());
        }
        report("case2", writer.logs);
    }

    @Test
    void 케이스3_이미지_파트() throws IOException {
        byte[] png = textImage("DOCAI 42");
        GptRequest request = GptRequest.of(null,
                List.of(GptContentPart.text("이미지 속 글자를 그대로 적어 줘."), GptContentPart.image(png, "image/png")),
                "live_image", IMAGE_SCHEMA);

        ImageText result = callOrFail(GptPurpose.EXTRACT, request, ImageText.class);

        System.out.println("[LIVE] case3 image bytes=" + png.length + ", text=" + result.text()
                + ", containsExpected=" + result.text().replace(" ", "").contains("DOCAI42"));
        report("case3", writer.logs);
        assertThat(writer.logs).extracting(AiCallLogCommand::status).containsExactly(AiCallStatus.OK);
        assertThat(result.text()).isNotBlank();
    }

    static byte[] textImage(String text) throws IOException {
        System.setProperty("java.awt.headless", "true");
        BufferedImage image = new BufferedImage(320, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
            g.drawString(text, 20, 65);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
