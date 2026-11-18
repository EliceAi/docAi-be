package com.team.docai.domain.analysis.service.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptClientTestSupport;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.domain.analysis.dto.AiCallLogCommand;
import com.team.docai.domain.analysis.dto.JudgeResponse;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.LawHint;
import com.team.docai.domain.analysis.entity.RefFoundBy;
import com.team.docai.domain.analysis.service.AiCallLogWriter;
import com.team.docai.global.config.OpenAiClientConfig;
import com.team.docai.global.config.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 GPT(설정 기본 모델)로 2차 지시문·스키마를 한 번 돌려 본다. 평소에는 건너뛴다 (DOCAI_OPENAI_LIVE_TEST=true 일 때만).
 * 보내는 글은 이 테스트가 지은 예시 조항과 근로기준법 제20조 문장뿐이다 (실제 계약서·사진 없음). 키는 출력하지 않는다.
 * 보는 것: strict 스키마가 거절되지 않는지, 응답이 JudgeResponse 로 읽히는지, 검증을 거친 뒤 보낸 조항만 남는지.
 */
@EnabledIfEnvironmentVariable(named = "DOCAI_OPENAI_LIVE_TEST", matches = "true")
class JudgeLiveTest {

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

    static AnalysisClause clause(long id, String key, String no, String title, String text, boolean issue) {
        AnalysisClause c = AnalysisClause.create(1L, key, no, title, text, title + " 요약", 1, null, issue,
                issue ? "퇴사하면 미리 정한 금액을 물어냄" : null, null,
                issue ? List.of(new LawHint("근로기준법", "제20조")) : List.of(), List.of());
        ReflectionTestUtils.setField(c, "clauseId", id);
        return c;
    }

    @Test
    void 예시_교육비_반환_조항을_판단한다() throws IOException {
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

        AnalysisClause term = clause(1, "c1", "제1조", "계약 기간", "계약기간: 2026.12.01 ~ 2027.02.28", false);
        AnalysisClause penalty = clause(7, "c7", "제7조", "교육비 반환",
                "근로자가 계약일로부터 1년 이내에 퇴사하는 경우 교육비 30만 원을 사업주에게 반환한다.", true);
        AnalysisClauseRef ref = AnalysisClauseRef.create(7L, "L1", 1L, null, RefFoundBy.TAG_MAP, false,
                "근로기준법 제20조 (위약 예정의 금지)",
                "사용자는 근로계약 불이행에 대한 위약금 또는 손해배상액을 예정하는 계약을 체결하지 못한다.");
        GptRequest request = GptRequest.of(JudgePrompt.SYSTEM_PROMPT,
                JudgePrompt.userContent(List.of(JudgePrompt.IndexEntry.from(term), JudgePrompt.IndexEntry.from(penalty)),
                        List.of(), List.of(new JudgePrompt.ClauseInput(penalty, List.of(ref), List.of(term))), false),
                JudgePrompt.SCHEMA_NAME, JudgePrompt.schema());

        JudgeResponse response;
        try {
            response = client.call(GptPurpose.JUDGE, request, JudgeResponse.class, GptContext.none());
        } catch (GptCallException e) {
            System.out.println("[LIVE-JUDGE] FAILED reason=" + e.getReason() + " logs=" + summary(logWriter.logs));
            throw new AssertionError("GPT 2차 실패: " + e.getReason(), null);
        }
        Map<String, JudgeValidator.SentClause> sent = new LinkedHashMap<>();
        sent.put("c7", new JudgeValidator.SentClause("c7", false, Set.of("L1"), Set.of("L1")));
        JudgeValidator.Result result = JudgeValidator.validate(JudgeValidator.STAGE_JUDGE, response, sent,
                Set.of("c1", "c7"), true);

        System.out.println("[LIVE-JUDGE] model=" + properties.model() + " logs=" + summary(logWriter.logs));
        System.out.println("[LIVE-JUDGE] judged=" + result.judgments() + " needMore=" + result.needMore()
                + " questions=" + result.questions() + " validation=" + result.log());
        assertThat(result.judgments()).containsOnlyKeys("c7");
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
