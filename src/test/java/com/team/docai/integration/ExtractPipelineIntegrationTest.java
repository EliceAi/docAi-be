package com.team.docai.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptContentPart;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.domain.analysis.dto.ExtractResponse;
import com.team.docai.domain.analysis.entity.LawHint;
import com.team.docai.domain.contract.dto.CreateContractCommand;
import com.team.docai.domain.contract.service.ContractService;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 1차(ExtractStep)를 실제 DB·B 의 실제 계약서 등록 경로로 돌린다. GPT 만 가짜(@MockitoBean GptClient).
 * <ul>
 *   <li>7쪽 계약서 → 묶음 2개(1~5, 6~7) → 묶음 결과 저장 → 병합(경계 조항 합치기, 키 c1..) → 조항·주요 조건·관련 조항 저장.</li>
 *   <li>지금은 2차(판정) 단계가 없어 JUDGING 조항이 남으므로 분석은 COMPLETED 가 되지 않고 FAILED · TEMPORARY_ERROR 가 정상이다.</li>
 *   <li>이 테스트가 만든 행과 이미지 파일만 ID 로 지운다. 꼬리표·법령·문서 종류는 읽기만 한다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class ExtractPipelineIntegrationTest {

    static final Pattern RANGE = Pattern.compile("(\\d+)~(\\d+)쪽 묶음");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TokenService tokenService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContractService contractService;
    @Autowired FileStorage storage;
    @Value("${docai.storage.root:./storage}") String storageRoot;
    @MockitoBean GptClient gptClient;

    final List<UUID> userIds = new ArrayList<>();
    final List<Long> contractIds = new ArrayList<>();
    final List<Long> versionIds = new ArrayList<>();
    final List<String> imageKeys = new ArrayList<>();
    final AtomicReference<BiFunction<Integer, Integer, ExtractResponse>> answer = new AtomicReference<>();
    final List<GptContext> contexts = Collections.synchronizedList(new ArrayList<>());
    UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                userId, String.format("%064x", System.nanoTime()));
        userIds.add(userId);
        when(gptClient.call(eq(GptPurpose.EXTRACT), any(), eq(ExtractResponse.class), any())).thenAnswer(inv -> {
            GptRequest request = inv.getArgument(1);
            contexts.add(inv.getArgument(3));
            Matcher m = RANGE.matcher(((GptContentPart.Text) request.userContent().get(0)).text());
            assertThat(m.find()).isTrue();
            return answer.get().apply(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
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

    long[] newContract(int pages) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        long contractId = contractService.create(userId, UUID.randomUUID(), new CreateContractCommand("EMPLOYMENT",
                Collections.nCopies(pages, out.toByteArray()), Collections.nCopies(pages, 0))).contractId();
        contractIds.add(contractId);
        var info = contractService.getForAnalysis(userId, contractId);
        versionIds.add(info.versionId());
        info.pages().forEach(p -> imageKeys.add(p.imageKey()));
        return new long[]{contractId, info.versionId()};
    }

    long postAnalysis(long contractId) throws Exception {
        String body = mvc.perform(post("/api/contracts/{id}/analyses", contractId)
                        .header("Authorization", "Bearer " + tokenService.issue(userId)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("data").get("analysisId").asLong();
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

    static ExtractResponse.Clause clause(String key, int page, String no, String title, String text, boolean from,
                                         boolean to, boolean issue, List<String> related) {
        return new ExtractResponse.Clause(key, page, no, title, text, (title == null ? "이어짐" : title) + " 요약",
                page + "쪽", from, to, issue, issue ? "퇴사 시 금액을 물어냄" : null,
                issue ? "PENALTY_ON_RESIGNATION" : null,
                issue ? List.of(new LawHint("근로기준법", "제20조")) : List.of(),
                issue ? List.of("위약금") : List.of(), related);
    }

    static List<ExtractResponse.PageRead> pages(int from, int to, Integer partial) {
        List<ExtractResponse.PageRead> list = new ArrayList<>();
        for (int p = from; p <= to; p++) {
            list.add(new ExtractResponse.PageRead(p, partial != null && partial == p ? "PARTIAL" : "READ"));
        }
        return list;
    }

    /** 1~5쪽: 제1조(1쪽), 제7조(5쪽, 다음 쪽으로 이어짐). 6~7쪽: 이어진 글(6쪽), 제8조(7쪽) */
    static ExtractResponse normal(int from, int to) {
        if (from == 1) {
            return new ExtractResponse("표준근로계약서", "2026-11-20", new ExtractResponse.TypeCheck(true, null),
                    pages(1, 5, 3),
                    List.of(clause("c1", 1, "제1조", "근로계약기간", "계약기간: 2026.12.01부터", false, false, false, List.of()),
                            clause("c2", 5, "제7조", "교육비 반환", "근로자가 1년 이내 퇴사하면", false, true, true,
                                    List.of("c1"))),
                    List.of(new ExtractResponse.KeyTerm("계약기간", "WRITTEN", "2026.12.01부터 기간 정함 없음",
                                    new ExtractResponse.ValueJson(null, null, null, null, "2026-12-01", null), "c1",
                                    "계약기간: 2026.12.01부터"),
                            new ExtractResponse.KeyTerm("임금", "MISSING", null, null, null, null)),
                    List.of(new ExtractResponse.DateItem("계약 시작일", "2026-12-01", "c1")));
        }
        return new ExtractResponse(null, null, new ExtractResponse.TypeCheck(true, null), pages(6, 7, null),
                List.of(clause("c1", 6, null, null, "교육비 30만 원을 반환한다.", true, false, false, List.of()),
                        clause("c2", 7, "제8조", "임금", "월 2,500,000원", false, false, false, List.of())),
                List.of(new ExtractResponse.KeyTerm("임금", "WRITTEN", "월 2,500,000원",
                        new ExtractResponse.ValueJson("MONTHLY", 2500000L, null, null, null, null), "c2", "월 2,500,000원")),
                List.of());
    }

    @Test
    void 묶음_두_개를_읽어_병합_결과를_저장하고_판정_중_조항이_남아_FAILED가_된다() throws Exception {
        answer.set(ExtractPipelineIntegrationTest::normal);
        long[] c = newContract(7);

        long analysisId = postAnalysis(c[0]);
        Map<String, Object> row = awaitFinished(analysisId);

        // 묶음별 결과
        assertThat(jdbc.queryForList("select chunk_no || ':' || status || ':' || retry_count || ':' || (result_json is not null)"
                + " from analysis_chunk where analysis_id = ? order by chunk_no", String.class, analysisId))
                .containsExactly("1:DONE:0:true", "2:DONE:0:true");
        assertThat(contexts).extracting(GptContext::analysisId).containsOnly(analysisId);
        assertThat(contexts).extracting(GptContext::userId).containsOnly(userId);
        assertThat(contexts).extracting(GptContext::chunkId).doesNotContainNull().hasSize(2);

        // 조항: 경계 조항 합침, 키 c1..c3, 판정 중
        List<Map<String, Object>> clauses = jdbc.queryForList("select clause_id, clause_key, article_no, page_no, verdict,"
                + " excluded, original_text, issue_found, issue_tag_code from analysis_clause where analysis_id = ?"
                + " order by clause_key", analysisId);
        assertThat(clauses).extracting(m -> m.get("clause_key") + ":" + m.get("article_no") + ":" + m.get("page_no"))
                .containsExactly("c1:제1조:1", "c2:제7조:5", "c3:제8조:7");
        assertThat(clauses).extracting(m -> m.get("excluded")).containsOnly(false);
        assertThat(clauses.get(1).get("original_text")).isEqualTo("근로자가 1년 이내 퇴사하면\n교육비 30만 원을 반환한다.");
        assertThat(clauses.get(1).get("issue_tag_code")).isEqualTo("PENALTY_ON_RESIGNATION");
        assertThat(jdbc.queryForObject("select count(*) from analysis_clause where analysis_id = ? and verdict = 'JUDGING'",
                Long.class, analysisId)).isPositive();

        // 관련 조항 (새 키 → 조항 ID), 주요 조건 (clause_id 연결, value_json)
        Long c1 = (Long) clauses.get(0).get("clause_id");
        Long c2 = (Long) clauses.get(1).get("clause_id");
        Long c3 = (Long) clauses.get(2).get("clause_id");
        assertThat(jdbc.queryForList("select clause_id || '>' || related_clause_id from analysis_clause_relation"
                + " where analysis_id = ?", String.class, analysisId)).containsExactly(c2 + ">" + c1);
        Map<String, Object> wage = jdbc.queryForMap("select clause_id, value, value_json::text as vj, state, source"
                + " from analysis_key_term where analysis_id = ? and label = '임금'", analysisId);
        assertThat(wage.get("clause_id")).isEqualTo(c3);
        assertThat(wage.get("value")).isEqualTo("월 2,500,000원");
        JsonNode vj = mapper.readTree((String) wage.get("vj"));
        assertThat(vj.get("type").asText()).isEqualTo("MONTHLY");
        assertThat(vj.get("amount").asLong()).isEqualTo(2500000L);
        assertThat(wage.get("state")).isEqualTo("OK");
        assertThat(wage.get("source")).isEqualTo("WRITTEN");
        assertThat(jdbc.queryForObject("select count(*) from analysis_key_term where analysis_id = ?", Long.class, analysisId))
                .isEqualTo(8L);
        assertThat(jdbc.queryForObject("select state from analysis_key_term where analysis_id = ? and label = '휴일'",
                String.class, analysisId)).isEqualTo("MISSING");

        // 분석 칸
        Map<String, Object> saved = jdbc.queryForMap("select extraction_json::text as ex, clause_index_json::text as ci,"
                + " partial_pages::text as pp, law_base_date::text as lbd, validation_log_json::text as vl"
                + " from analysis where analysis_id = ?", analysisId);
        assertThat(mapper.readTree((String) saved.get("ex")).get("clauses")).hasSize(3);
        assertThat(mapper.readTree((String) saved.get("ci")).get(2).get("clauseKey").asText()).isEqualTo("c3");
        assertThat(saved.get("pp")).isEqualTo("[3]");
        assertThat(saved.get("lbd")).isEqualTo("2026-11-20");
        assertThat(mapper.readTree((String) saved.get("vl")).get("extract").toString()).contains("BOUNDARY_MERGED");

        // 2차(판정) 단계가 아직 없으므로 JUDGING 이 남아 COMPLETED 가 아니라 FAILED · TEMPORARY_ERROR (3단계 규칙)
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("fail_reason")).isEqualTo("TEMPORARY_ERROR");
        assertThat(jdbc.queryForObject("select latest_analysis_id from contract_version where version_id = ?",
                Long.class, c[1])).isNull();
    }

    @Test
    void 고른_종류와_다른_문서면_TYPE_MISMATCH와_실제_종류_이름이고_조항을_저장하지_않는다() throws Exception {
        answer.set((from, to) -> new ExtractResponse(null, null, new ExtractResponse.TypeCheck(false, "주택 임대차계약서"),
                pages(from, to, null), List.of(), List.of(), List.of()));
        long[] c = newContract(2);

        long analysisId = postAnalysis(c[0]);
        Map<String, Object> row = awaitFinished(analysisId);

        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("fail_reason")).isEqualTo("TYPE_MISMATCH");
        assertThat(row.get("detected_type_name")).isEqualTo("주택 임대차계약서");
        assertThat(row.get("progress_step")).isEqualTo("READING_TEXT");
        assertThat(jdbc.queryForObject("select count(*) from analysis_clause where analysis_id = ?", Long.class, analysisId))
                .isZero();
    }

    @Test
    void 글자를_못_읽은_쪽이_있으면_PAGE_UNREADABLE과_쪽_번호() throws Exception {
        answer.set((from, to) -> new ExtractResponse(null, null, new ExtractResponse.TypeCheck(true, null),
                List.of(new ExtractResponse.PageRead(1, "READ"), new ExtractResponse.PageRead(2, "UNREADABLE")),
                List.of(), List.of(), List.of()));
        long[] c = newContract(2);

        long analysisId = postAnalysis(c[0]);
        Map<String, Object> row = awaitFinished(analysisId);

        assertThat(row.get("fail_reason")).isEqualTo("PAGE_UNREADABLE");
        assertThat(row.get("fail_page_no")).isEqualTo(2);
    }
}
