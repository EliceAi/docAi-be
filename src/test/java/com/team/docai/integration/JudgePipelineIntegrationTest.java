package com.team.docai.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptContentPart;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.domain.analysis.dto.ExtractResponse;
import com.team.docai.domain.analysis.dto.JudgeResponse;
import com.team.docai.domain.analysis.entity.LawHint;
import com.team.docai.domain.contract.dto.CreateContractCommand;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.global.auth.TokenService;
import com.team.docai.global.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 요청부터 완료까지 (1차 → 숫자 규칙 → 근거 찾기 → 2차 → 검증 → 저장 → COMPLETED) 를 실제 DB·B 의 실제 경로로 돌린다.
 * GPT 만 가짜(@MockitoBean GptClient). 알림은 B 의 실제 빈을 감싼 스파이로 호출 인자만 본다.
 * <ul>
 *   <li>1쪽 계약서: c1 계약기간(문제 없음), c2 교육비 반환(위약금 꼬리표 → 근로기준법 제20조 근거), c3 관할 법원(근거 없음).</li>
 *   <li>2차 가짜: c2 NEEDS_CHECK(준 ID L1 + 없는 ID L99), c3 NEEDS_CHECK(인용 없음), 모르는 키 c50.</li>
 *   <li>법령·꼬리표·문서 종류는 운영 데이터라 읽기만 한다. 이 테스트가 만든 행과 이미지 파일만 ID 로 지운다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class JudgePipelineIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TokenService tokenService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContractService contractService;
    @Autowired FileStorage storage;
    @Value("${docai.storage.root:./storage}") String storageRoot;
    @MockitoBean GptClient gptClient;
    @MockitoSpyBean NotificationService notificationService;

    final List<UUID> userIds = new ArrayList<>();
    final List<Long> contractIds = new ArrayList<>();
    final List<Long> versionIds = new ArrayList<>();
    final List<String> imageKeys = new ArrayList<>();
    final List<GptRequest> judgeRequests = new CopyOnWriteArrayList<>();
    final AtomicReference<Supplier<JudgeResponse>> judgeAnswer = new AtomicReference<>(JudgePipelineIntegrationTest::judge);
    UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                userId, String.format("%064x", System.nanoTime()));
        userIds.add(userId);
        when(gptClient.call(eq(GptPurpose.EXTRACT), any(), eq(ExtractResponse.class), any()))
                .thenAnswer(inv -> extract());
        when(gptClient.call(eq(GptPurpose.JUDGE), any(), eq(JudgeResponse.class), any())).thenAnswer(inv -> {
            judgeRequests.add(inv.getArgument(1));
            return judgeAnswer.get().get();
        });
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        for (int i = 0; i < 100 && countAnalyzing() > 0; i++) {
            Thread.sleep(100);
        }
        for (Long versionId : versionIds) {
            String analyses = "select analysis_id from analysis where version_id = " + versionId;
            String clauses = "select clause_id from analysis_clause where analysis_id in (" + analyses + ")";
            jdbc.update("update contract_version set latest_analysis_id = null where version_id = ?", versionId);
            jdbc.update("delete from analysis_clause_ref where clause_id in (" + clauses + ")");
            jdbc.update("delete from analysis_clause_relation where analysis_id in (" + analyses + ")");
            jdbc.update("delete from analysis_key_term where analysis_id in (" + analyses + ")");
            jdbc.update("delete from analysis_finding where analysis_id in (" + analyses + ")");
            jdbc.update("delete from schedule_candidate where analysis_id in (" + analyses + ")");
            jdbc.update("delete from analysis_clause where analysis_id in (" + analyses + ")");
            jdbc.update("delete from ai_call_log where analysis_id in (" + analyses + ")");
            jdbc.update("delete from notification where analysis_id in (" + analyses + ")");
            jdbc.update("delete from analysis_chunk where analysis_id in (" + analyses + ")");
            jdbc.update("delete from analysis where version_id = ?", versionId);
            jdbc.update("delete from contract_page where version_id = ?", versionId);
            jdbc.update("delete from contract_version where version_id = ?", versionId);
        }
        for (Long id : contractIds) {
            jdbc.update("delete from contract where contract_id = ?", id);
        }
        for (UUID id : userIds) {
            jdbc.update("delete from notification where user_id = ?", id);
            jdbc.update("delete from ai_call_log where user_id = ?", id);
            jdbc.update("delete from idempotency_record where user_id = ?", id);
            jdbc.update("delete from users where user_id = ?", id);
        }
        for (String key : imageKeys) {
            storage.delete(key);
        }
        for (UUID id : userIds) {
            try {
                Files.deleteIfExists(Path.of(storageRoot).toAbsolutePath().normalize().resolve("contracts").resolve(id.toString()));
            } catch (IOException ignored) {
                // 다른 파일이 남아 있으면 지우지 않는다
            }
        }
    }

    long countAnalyzing() {
        if (versionIds.isEmpty()) {
            return 0;
        }
        String in = String.join(",", Collections.nCopies(versionIds.size(), "?"));
        return jdbc.queryForObject("select count(*) from analysis where status = 'ANALYZING' and version_id in (" + in + ")",
                Long.class, versionIds.toArray());
    }

    static ExtractResponse.Clause clause(String key, String no, String title, String text, boolean issue, String tag,
                                         List<LawHint> hints, List<String> keywords, List<String> related) {
        return new ExtractResponse.Clause(key, 1, no, title, text, title + " 요약", "1쪽", false, false, issue,
                issue ? title + " 의심" : null, tag, hints, keywords, related);
    }

    static ExtractResponse extract() {
        return new ExtractResponse("카페 알바 근로계약서", "2026-11-20", new ExtractResponse.TypeCheck(true, null),
                List.of(new ExtractResponse.PageRead(1, "READ")),
                List.of(clause("c1", "제1조", "근로계약기간", "계약기간: 2026.12.01 ~ 2027.02.28", false, null,
                                List.of(), List.of(), List.of()),
                        clause("c2", "제7조", "교육비 반환", "근로자가 1년 이내 퇴사하면 교육비 30만 원을 반환한다.", true,
                                "PENALTY_ON_RESIGNATION", List.of(new LawHint("근로기준법", "제20조")), List.of("위약금"),
                                List.of("c1")),
                        clause("c3", "제12조", "관할 법원", "분쟁은 회사 소재지 법원에서 다툰다.", true, null,
                                List.of(), List.of(), List.of())),
                List.of(new ExtractResponse.KeyTerm("계약기간", "WRITTEN", "2026.12.01 ~ 2027.02.28",
                                new ExtractResponse.ValueJson(null, null, null, null, "2026-12-01", "2027-02-28"), "c1",
                                "계약기간: 2026.12.01 ~ 2027.02.28"),
                        new ExtractResponse.KeyTerm("임금", "WRITTEN", "월 2,500,000원",
                                new ExtractResponse.ValueJson("MONTHLY", 2500000L, null, null, null, null), null, null)),
                List.of(new ExtractResponse.DateItem("계약 시작일", "2026-12-01", "c1"),
                        new ExtractResponse.DateItem("계약 종료일", "2027-02-28", "c1"),
                        new ExtractResponse.DateItem("계약일", "2026-11-20", null)));
    }

    static JudgeResponse judge() {
        return new JudgeResponse(List.of(
                new JudgeResponse.Judgment("c2", "NEEDS_CHECK", "1년 안에 퇴사하면 교육비 30만 원 반환",
                        "1년이 되기 전에 그만두면 교육비를 돌려줘야 한다는 약속이에요.",
                        "계약 기간이 3개월이라 사실상 교육비를 내야 해요. 이 조항은 무효입니다.",
                        List.of("L1", "L99"), List.of("c1"), List.of("교육 내용과 실제 비용을 알려 주실 수 있나요?")),
                new JudgeResponse.Judgment("c3", "NEEDS_CHECK", "분쟁 법원이 회사 쪽으로 정해져 있어요", "다툼이 생기면 회사 근처 법원에서 해요.",
                        "멀리 있는 법원에 가야 할 수 있어요.", List.of(), List.of(), List.of()),
                new JudgeResponse.Judgment("c50", "OK", null, null, null, List.of(), List.of(), List.of())),
                List.of(),
                List.of("교육비 반환 조항은 빼 주실 수 있나요?"));
    }

    @Test
    void 분석이_2차_판단과_검증을_거쳐_COMPLETED가_되고_결과_조회가_완료_결과를_돌려준다() throws Exception {
        long[] c = newContract();
        long contractId = c[0];
        long versionId = c[1];
        String token = "Bearer " + tokenService.issue(userId);
        long analysisId = postAnalysis(contractId, token);
        Map<String, Object> row = awaitFinished(analysisId);

        // 분석: COMPLETED · DONE, 최신 분석 연결, 프롬프트 버전
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("progress_step")).isEqualTo("DONE");
        assertThat(row.get("fail_reason")).isNull();
        assertThat(row.get("prompt_version")).isEqualTo("extract-v4/judge-v2");
        assertThat(jdbc.queryForObject("select latest_analysis_id from contract_version where version_id = ?",
                Long.class, versionId)).isEqualTo(analysisId);
        // 계약서 이름은 완료 트랜잭션이 끝난 뒤에 넣는다
        assertThat(awaitTitle(contractId)).isEqualTo("카페 알바 근로계약서");

        // 2차에는 이미지 없이 글만, 의심 조항(c2, c3)만 한 번에
        assertThat(judgeRequests).hasSize(1);
        GptRequest request = judgeRequests.get(0);
        assertThat(request.userContent()).allMatch(p -> p instanceof GptContentPart.Text);
        String sent = ((GptContentPart.Text) request.userContent().get(1)).text();
        assertThat(sent).contains("### c2 ", "### c3 ", "[L1] 근로기준법 제20조", "관련 조항 원문", "계약기간: 2026.12.01")
                .doesNotContain("### c1 ");

        // 조항 판정: c1 OK(의심 없음), c2 NEEDS_CHECK(L1 만 인용), c3 UNCERTAIN(근거 없음)
        Map<String, Map<String, Object>> clauses = new java.util.HashMap<>();
        jdbc.queryForList("select clause_id, clause_key, verdict, basis_type, headline, why_check, priority,"
                + " ask_counterpart_json::text as ask from analysis_clause where analysis_id = ?", analysisId)
                .forEach(m -> clauses.put((String) m.get("clause_key"), m));
        assertThat(clauses.get("c1").get("verdict")).isEqualTo("OK");
        assertThat(clauses.get("c2").get("verdict")).isEqualTo("NEEDS_CHECK");
        assertThat(clauses.get("c2").get("basis_type")).isEqualTo("AI_INTERPRETATION");
        assertThat(clauses.get("c2").get("why_check")).isEqualTo("계약 기간이 3개월이라 사실상 교육비를 내야 해요.");
        assertThat(clauses.get("c2").get("priority")).isEqualTo(1);
        assertThat(clauses.get("c3").get("verdict")).isEqualTo("UNCERTAIN");
        assertThat(clauses.get("c3").get("priority")).isEqualTo(2);
        Long c2 = (Long) clauses.get("c2").get("clause_id");
        assertThat(jdbc.queryForList("select ref_label from analysis_clause_ref where clause_id = ? and cited = true",
                String.class, c2)).containsExactly("L1");
        assertThat(jdbc.queryForObject("select count(*) from analysis_clause where analysis_id = ? and verdict = 'JUDGING'",
                Long.class, analysisId)).isZero();

        // 검증 기록, 서명 전 질문, 일정 후보
        Map<String, Object> saved = jdbc.queryForMap("select validation_log_json::text as vl, questions_json::text as q"
                + " from analysis where analysis_id = ?", analysisId);
        JsonNode judgeLog = mapper.readTree((String) saved.get("vl")).get("judge");
        assertThat(judgeLog.toString()).contains("CITATION_REMOVED", "VERDICT_DOWNGRADED", "JUDGMENT_UNKNOWN_KEY",
                "ASSERTION_REMOVED");
        assertThat(mapper.readTree((String) saved.get("vl")).has("extract")).isTrue();
        assertThat(mapper.readTree((String) saved.get("q")).get(0).asText()).isEqualTo("교육비 반환 조항은 빼 주실 수 있나요?");
        assertThat(jdbc.queryForList("select date_kind || ':' || date || ':' || source || ':' || default_selected"
                        + " from schedule_candidate where analysis_id = ? order by candidate_id", String.class, analysisId))
                .containsExactly("CONTRACT_START:2026-12-01:WRITTEN:true", "CONTRACT_END:2027-02-28:WRITTEN:true");

        // 완료 알림 (B6): 확인 필요 = NEEDS_CHECK 1 + UNCERTAIN 1
        verify(AopTestUtils.<NotificationService>getUltimateTargetObject(notificationService)).createAnalysisResult(
                userId, analysisId, "ANALYSIS_DONE", "분석이 끝났어요", "카페 알바 근로계약서 · 확인 필요 2");

        // 결과 조회 API (10단계)
        mvc.perform(get("/api/analyses/{id}", analysisId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progressStep").value("DONE"))
                .andExpect(jsonPath("$.data.failure").value(nullValue()))
                .andExpect(jsonPath("$.data.result.title").value("카페 알바 근로계약서"))
                .andExpect(jsonPath("$.data.result.counts.needsCheck").value(2))
                .andExpect(jsonPath("$.data.result.counts.totalClauses").value(3))
                .andExpect(jsonPath("$.data.result.priorityClauses", hasSize(2)))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].verdict").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].title").value("1년 안에 퇴사하면 교육비 30만 원 반환"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].laws[0].lawName").value("근로기준법"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].laws[0].article").value("제20조"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].relatedClauses[0].articleNo").value("제1조"))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].verdict").value("UNCERTAIN"))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].laws", hasSize(0)))
                .andExpect(jsonPath("$.data.result.priorityClauses[1].description").value(nullValue()))
                .andExpect(jsonPath("$.data.result.clauses[*].verdict", contains("OK", "NEEDS_CHECK", "UNCERTAIN")))
                .andExpect(jsonPath("$.data.result.questionsBeforeSigning", contains("교육비 반환 조항은 빼 주실 수 있나요?")))
                .andExpect(jsonPath("$.data.result.foundDateCount").value(2));
    }

    @Test
    void 다시_불러도_판단이_오지_않은_의심_조항이_있으면_FAILED이고_최신_분석_연결과_완료_알림이_없다() throws Exception {
        // c3 판단이 계속 빠짐 (처음 1번 + 빠진 조항만 다시 1번)
        judgeAnswer.set(() -> new JudgeResponse(List.of(judge().judgments().get(0)), List.of(), List.of()));
        long[] c = newContract();
        String token = "Bearer " + tokenService.issue(userId);

        long analysisId = postAnalysis(c[0], token);
        Map<String, Object> row = awaitFinished(analysisId);

        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("fail_reason")).isEqualTo("TEMPORARY_ERROR");
        assertThat(judgeRequests).hasSize(2);
        assertThat(((GptContentPart.Text) judgeRequests.get(1).userContent().get(1)).text())
                .contains("### c3 ").doesNotContain("### c2 ");
        assertThat(jdbc.queryForObject("select latest_analysis_id from contract_version where version_id = ?",
                Long.class, c[1])).isNull();
        assertThat(jdbc.queryForObject("select verdict from analysis_clause where analysis_id = ? and clause_key = 'c3'",
                String.class, analysisId)).isEqualTo("JUDGING");
        NotificationService spy = AopTestUtils.getUltimateTargetObject(notificationService);
        verify(spy, never()).createAnalysisResult(any(), eq(analysisId), eq("ANALYSIS_DONE"), any(), any());
        verify(spy).createAnalysisResult(eq(userId), eq(analysisId), eq("ANALYSIS_FAILED"), any(), any());
        // 완료되지 않았으므로 계약서 이름도 넣지 않는다
        assertThat(jdbc.queryForObject("select title from contract where contract_id = ?", String.class, c[0])).isNull();
    }

    long[] newContract() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        long contractId = contractService.create(userId, UUID.randomUUID(), new CreateContractCommand("EMPLOYMENT",
                List.of(out.toByteArray()), List.of(0))).contractId();
        contractIds.add(contractId);
        var info = contractService.getForAnalysis(userId, contractId);
        versionIds.add(info.versionId());
        info.pages().forEach(p -> imageKeys.add(p.imageKey()));
        return new long[]{contractId, info.versionId()};
    }

    long postAnalysis(long contractId, String token) throws Exception {
        String body = mvc.perform(post("/api/contracts/{id}/analyses", contractId).header("Authorization", token))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("data").get("analysisId").asLong();
    }

    String awaitTitle(long contractId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            String title = jdbc.queryForObject("select title from contract where contract_id = ?", String.class, contractId);
            if (title != null) {
                return title;
            }
            Thread.sleep(100);
        }
        return null;
    }

    Map<String, Object> awaitFinished(long analysisId) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            Map<String, Object> row = jdbc.queryForMap("select * from analysis where analysis_id = ?", analysisId);
            if (!"ANALYZING".equals(row.get("status"))) {
                return row;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("분석이 끝나지 않음: " + analysisId);
    }
}
