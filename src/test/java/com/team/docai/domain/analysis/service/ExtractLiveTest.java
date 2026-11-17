package com.team.docai.domain.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptClientTestSupport;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.domain.analysis.dto.AiCallLogCommand;
import com.team.docai.domain.analysis.dto.ExtractResponse;
import com.team.docai.domain.law.dto.IssueTagInfo;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 GPT(설정 기본 모델 gpt-5.5)로 1차를 한 번 돌려 본다. 평소에는 건너뛴다 (DOCAI_OPENAI_LIVE_TEST=true 일 때만).
 * <ul>
 *   <li>보내는 사진은 테스트 정답세트의 사본만: 01(만든 예시 계약서), 02 의 "가린사진" 폴더(사용자가 가린 사본). 원본은 보내지 않는다.</li>
 *   <li>결과(GPT 응답, 병합 결과, 토큰·시간)는 DOCAI_LIVE_OUT 폴더(없으면 build/extract-live)에 파일로 남긴다. 키는 출력하지 않는다.</li>
 *   <li>꼬리표 목록은 DB 없이 꼬리표_초안.md 의 근로 12개를 그대로 쓴다 (DB issue_tag 와 같음).</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "DOCAI_OPENAI_LIVE_TEST", matches = "true")
class ExtractLiveTest {

    static final Path SETS = Path.of(System.getProperty("user.home"), "documentAi", "테스트_정답세트");
    static final List<IssueTagInfo> EMPLOYMENT_TAGS = List.of(
            new IssueTagInfo("ANNUAL_LEAVE_RESTRICTION", "연차휴가 제한·대체"),
            new IssueTagInfo("DELAYED_SETTLEMENT", "퇴직 후 금품청산 지연"),
            new IssueTagInfo("DISMISSAL_TERMS", "해고 조건"),
            new IssueTagInfo("EXCESSIVE_OVERTIME", "연장근로 한도"),
            new IssueTagInfo("INCLUSIVE_WAGE", "포괄임금·고정 연장수당"),
            new IssueTagInfo("MIN_WAGE_SHORTFALL", "최저임금 미달"),
            new IssueTagInfo("MISSING_REQUIRED_TERMS", "필수 기재사항 빠짐"),
            new IssueTagInfo("NO_BREAK_TIME", "휴게시간 없음·부족"),
            new IssueTagInfo("NO_WEEKLY_HOLIDAY", "주휴일 없음"),
            new IssueTagInfo("PENALTY_ON_RESIGNATION", "퇴사 위약금·손해배상 예정"),
            new IssueTagInfo("SEVERANCE_WAIVER", "퇴직금 포기·미지급"),
            new IssueTagInfo("UNILATERAL_CHANGE", "근로조건·업무 일방 변경"));

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

    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void 세트01_만든_근로계약서_2쪽() throws IOException {
        Path dir = SETS.resolve("01_근로_정상").resolve("이미지");
        run("01", List.of(dir.resolve("page1.jpg"), dir.resolve("page2.jpg")),
                SETS.resolve("01_근로_정상").resolve("정답.json"));
    }

    @Test
    void 세트02_가린_실물_근로계약서_2쪽() throws IOException {
        Path dir = SETS.resolve("02_실물_근로연봉").resolve("가린사진");
        run("02", List.of(dir.resolve("page3_근로1.jpg"), dir.resolve("page4_근로2.jpg")), null);
    }

    void run(String name, List<Path> images, Path answerFile) throws IOException {
        for (Path p : images) {
            assertThat(p.toString()).doesNotContain("레퍼런스");
            assertThat(Files.exists(p)).as(p.getFileName().toString()).isTrue();
        }
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

        List<ExtractPrompt.PageImage> pages = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            pages.add(new ExtractPrompt.PageImage(i + 1, Files.readAllBytes(images.get(i)), "image/jpeg"));
        }
        GptRequest request = GptRequest.of(ExtractPrompt.systemPrompt("EMPLOYMENT", EMPLOYMENT_TAGS),
                ExtractPrompt.userContent(1, pages.size(), pages.size(), pages), ExtractPrompt.SCHEMA_NAME,
                ExtractPrompt.schema("EMPLOYMENT", EMPLOYMENT_TAGS));

        long started = System.nanoTime();
        ExtractResponse response;
        try {
            response = client.call(GptPurpose.EXTRACT, request, ExtractResponse.class, GptContext.none());
        } catch (GptCallException e) {
            System.out.println("[LIVE-EXTRACT] " + name + " FAILED reason=" + e.getReason() + " logs=" + summary(logWriter.logs));
            throw new AssertionError("GPT 1차 실패: " + e.getReason(), null);
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        ExtractMerger.Result merged = ExtractMerger.merge("EMPLOYMENT",
                List.of(new ExtractMerger.ChunkResult(1, 1, pages.size(), response)),
                EMPLOYMENT_TAGS.stream().map(IssueTagInfo::code).collect(Collectors.toSet()), LocalDate.now());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set", name);
        out.put("model", properties.model());
        out.put("reasoningEffort", properties.purpose("EXTRACT").reasoningEffort());
        out.put("elapsedMs", elapsedMs);
        out.put("calls", logWriter.logs.stream().map(l -> Map.of("status", l.status().name(), "model", l.model(),
                "latencyMs", l.latencyMs(), "inputTokens", String.valueOf(l.inputTokens()),
                "cachedInputTokens", String.valueOf(l.cachedInputTokens()),
                "outputTokens", String.valueOf(l.outputTokens()))).toList());
        out.put("failure", merged.failure());
        out.put("partialPages", merged.partialPages());
        out.put("clauses", merged.clauses());
        out.put("keyTerms", merged.keyTerms());
        out.put("relations", merged.relations());
        out.put("validationLog", merged.validationLog());
        out.put("extraction", merged.extraction());
        out.put("rawResponse", response);
        if (answerFile != null) {
            out.put("compare", compare(merged, mapper.readTree(answerFile.toFile())));
        }
        String dirName = System.getenv("DOCAI_LIVE_OUT");
        Path outDir = dirName == null || dirName.isBlank() ? Path.of("build", "extract-live") : Path.of(dirName);
        Files.createDirectories(outDir);
        Path file = outDir.resolve("extract-" + name + ".json");
        Files.writeString(file, mapper.writeValueAsString(out), StandardCharsets.UTF_8);

        System.out.println("[LIVE-EXTRACT] " + name + " elapsedMs=" + elapsedMs + " clauses=" + merged.clauses().size()
                + " issueFound=" + merged.clauses().stream().filter(ExtractMerger.MergedClause::issueFound).count()
                + " failure=" + merged.failure() + " partialPages=" + merged.partialPages()
                + " logs=" + summary(logWriter.logs) + " file=" + file.toAbsolutePath());
        if (answerFile != null) {
            System.out.println("[LIVE-EXTRACT] " + name + " compare=" + out.get("compare"));
        }
        assertThat(logWriter.logs).isNotEmpty();
    }

    Map<String, Object> compare(ExtractMerger.Result merged, JsonNode answer) {
        Map<String, Object> c = new LinkedHashMap<>();
        List<String> expectedArticles = new ArrayList<>();
        answer.get("clauses").forEach(n -> expectedArticles.add(n.get("articleNo").asText()));
        List<String> actualArticles = merged.clauses().stream().map(ExtractMerger.MergedClause::articleNo).toList();
        c.put("clauseCount", merged.clauses().size() + " / 정답 " + expectedArticles.size());
        c.put("missingArticles", expectedArticles.stream().filter(a -> !actualArticles.contains(a)).toList());
        c.put("extraArticles", actualArticles.stream().filter(a -> a == null || !expectedArticles.contains(a)).toList());
        c.put("issueFoundCount", merged.clauses().stream().filter(ExtractMerger.MergedClause::issueFound).count()
                + " (정답 0, 오탐 확인)");
        Map<String, String> terms = new LinkedHashMap<>();
        for (JsonNode t : answer.get("keyTerms")) {
            String label = t.get("label").asText();
            ExtractMerger.MergedKeyTerm got = merged.keyTerms().stream().filter(k -> k.label().equals(label))
                    .findFirst().orElse(null);
            terms.put(label, "정답 " + t.get("value").asText() + " " + t.get("valueJson") + " | 결과 "
                    + (got == null ? "없음" : got.value() + " " + got.valueJson() + " " + got.state()));
        }
        c.put("keyTerms", terms);
        ExtractMerger.MergedKeyTerm wage = merged.keyTerms().stream().filter(k -> k.label().equals("임금")).findFirst()
                .orElse(null);
        c.put("wageMatches", wage != null && wage.valueJson() != null
                && "MONTHLY".equals(wage.valueJson().get("type"))
                && Objects.equals(2500000L, ((Number) wage.valueJson().get("amount")).longValue()));
        return c;
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
