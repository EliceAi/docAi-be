package com.team.docai.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptClientTestSupport;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.dto.AiCallLogCommand;
import com.team.docai.domain.analysis.dto.AnalysisChatInfo;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.service.AiCallLogWriter;
import com.team.docai.domain.analysis.service.AssertionText;
import com.team.docai.domain.chat.dto.ChatGptResponse;
import com.team.docai.domain.chat.dto.LinkedDocumentInfo;
import com.team.docai.domain.chat.entity.ChatReference;
import com.team.docai.domain.chat.entity.ReferenceType;
import com.team.docai.global.config.OpenAiClientConfig;
import com.team.docai.global.config.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 GPT(설정 기본 모델)로 챗봇 지시문·스키마를 한 번 돌려 본다. 평소에는 건너뛴다 (DOCAI_OPENAI_LIVE_TEST=true 일 때만).
 * 보내는 글은 이 테스트가 지은 예시 분석 요약·조항과 근로기준법 제20조 문장뿐이다 (실제 계약서·사진 없음). 키는 출력하지 않는다.
 * 보는 것: strict 스키마가 거절되지 않는지, 응답이 ChatGptResponse 로 읽히는지, 검증 뒤 참고한 법령이 준 것뿐인지,
 * 답에 단정 표현·내부 키가 남지 않는지.
 */
@EnabledIfEnvironmentVariable(named = "DOCAI_OPENAI_LIVE_TEST", matches = "true")
class ChatLiveTest {

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

    @Test
    void 예시_교육비_반환_조항을_묻는다() throws IOException {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = readDotEnv("OPENAI_API_KEY");
        }
        assertThat(apiKey).as("OPENAI_API_KEY").isNotBlank();
        OpenAiProperties properties = new Binder(new MapConfigurationPropertySource(Map.of("openai.api-key", apiKey)))
                .bind("openai", OpenAiProperties.class).get();
        MemoryLogWriter logWriter = new MemoryLogWriter();
        GptClient client = GptClientTestSupport.create(new OpenAiClientConfig().openAiRestClient(properties),
                properties, logWriter, new ObjectMapper());

        AnalysisChatInfo.Clause penalty = new AnalysisChatInfo.Clause(707L, "c7", "제7조", "교육비 반환", "NEEDS_CHECK",
                "1년 안에 퇴사하면 교육비 30만 원 반환", "교육비 반환", 2, "1년 안에 그만두면 교육비를 내야 한다는 뜻이에요.",
                "계약 기간이 3개월이라 계약대로 일해도 1년 안에 끝나요.",
                "근로자가 계약일로부터 1년 이내에 퇴사하는 경우 교육비 30만 원을 사업주에게 반환한다.");
        AnalysisChatInfo analysis = new AnalysisChatInfo(88L, 12L, AnalysisStatus.COMPLETED, "EMPLOYMENT",
                OffsetDateTime.now(),
                List.of(new AnalysisChatInfo.KeyTerm("임금", "시급 10,000원", "NEEDS_CHECK"),
                        new AnalysisChatInfo.KeyTerm("계약 기간", "2026.12.01 ~ 2027.02.28", "OK")),
                List.of(new AnalysisChatInfo.Clause(701L, "c1", "제1조", "계약 기간", "OK", null, "3개월 계약", 1, null, null,
                        "계약기간: 2026.12.01 ~ 2027.02.28"), penalty),
                List.of(),
                List.of());
        ChatContext ctx = new ChatContext(UUID.randomUUID(), null, null, "교육비 반환 조항 괜찮은 거야? 서명해도 돼?", "S09",
                707L, 12L, 88L, null, new LinkedDocumentInfo(12L, "카페 알바 근로계약서", OffsetDateTime.now()),
                analysis, null, penalty, null,
                List.of(new ChatContext.Source("L1", 1L, "근로기준법 제20조", "근로기준법 제20조 (위약 예정의 금지)",
                        "사용자는 근로계약 불이행에 대한 위약금 또는 손해배상액을 예정하는 계약을 체결하지 못한다.")),
                List.of());

        ChatGptResponse response;
        try {
            response = client.call(GptPurpose.CHAT, ChatPrompt.request(ctx), ChatGptResponse.class, GptContext.none());
        } catch (GptCallException e) {
            System.out.println("[LIVE-CHAT] FAILED reason=" + e.getReason() + " logs=" + summary(logWriter.logs));
            throw new AssertionError("GPT 챗봇 실패: " + e.getReason(), null);
        }
        ChatAnswerValidator.Result result = ChatAnswerValidator.validate(response, ctx);

        System.out.println("[LIVE-CHAT] model=" + properties.model() + " logs=" + summary(logWriter.logs));
        System.out.println("[LIVE-CHAT] raw type=" + response.answerType() + " cited=" + response.citedIds()
                + " used=" + response.usedSources() + " mentionsLaw=" + response.mentionsLaw());
        System.out.println("[LIVE-CHAT] answerType=" + result.answerType() + " answer=" + result.answer());
        System.out.println("[LIVE-CHAT] references=" + result.references() + " partial=" + result.partial()
                + " actions=" + result.actions() + " validation=" + result.log());

        assertThat(result.answer()).isNotBlank();
        assertThat(AssertionText.contains(result.answer())).isFalse();
        assertThat(result.answer()).doesNotContainPattern("(?<![A-Za-z0-9_])c\\d+(?![A-Za-z0-9_])").doesNotContain("[L1]");
        assertThat(result.references()).filteredOn(r -> r.type() == ReferenceType.LAW_DB)
                .as("교육비 반환 질문에 준 근로기준법 제20조를 참고해야 한다")
                .isNotEmpty()
                .extracting(ChatReference::label).allMatch("근로기준법 제20조"::equals);
    }

    static String summary(List<AiCallLogCommand> logs) {
        return logs.stream().map(l -> l.status() + "/" + l.model() + "/" + l.latencyMs() + "ms/in=" + l.inputTokens()
                + ",cached=" + l.cachedInputTokens() + ",out=" + l.outputTokens()).toList().toString();
    }

    /** .env 의 NAME=value 한 줄 (값은 출력하지 않는다) */
    static String readDotEnv(String name) throws IOException {
        Path env = Path.of(".env");
        if (!Files.exists(env)) {
            return null;
        }
        for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.startsWith("export ")) {
                t = t.substring(7).trim();
            }
            if (t.startsWith(name + "=")) {
                String v = t.substring(name.length() + 1).trim();
                if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
                    int end = v.indexOf(v.charAt(0), 1);
                    return end > 0 ? v.substring(1, end) : v.substring(1);
                }
                int comment = v.indexOf(" #");
                return (comment >= 0 ? v.substring(0, comment) : v).trim();
            }
        }
        return null;
    }
}
