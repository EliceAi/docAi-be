package com.team.docai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptClientTestSupport;
import com.team.docai.domain.analysis.client.GptContentPart;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.global.config.OpenAiProperties;
import com.team.docai.domain.analysis.dto.AiCallLogCommand;
import com.team.docai.domain.analysis.entity.AiCallStatus;
import com.team.docai.domain.analysis.repository.AiCallLogRepository;
import com.team.docai.domain.analysis.service.AiCallLogWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ai_call_log 가 실제 DB 에 시도마다 남는지, 호출 쪽 트랜잭션이 롤백돼도 남는지(REQUIRES_NEW) 확인.
 * OpenAI 는 부르지 않는다 (MockRestServiceServer). 로컬 PostgreSQL 이 필요하다.
 * 같은 DB 를 다른 프로세스가 함께 쓸 수 있으므로, 테스트마다 고유한 model 값(test-...)을 붙여
 * 그 값으로만 조회·삭제한다. 다른 행은 건드리지 않는다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class AiCallLogIntegrationTest {

    static final String BASE_URL = "https://openai.test/v1";
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("title", Map.of("type", "string")),
            "required", List.of("title"),
            "additionalProperties", false);

    /** 모의 응답 본문. model 에 이 테스트의 고유값을 넣어 기록 행을 구분한다. */
    static String okBody(String model) {
        return "{\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"title\\\":\\\"근로계약서\\\"}\",\"refusal\":null}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":0}}}";
    }

    public record Title(String title) {
    }

    @Autowired AiCallLogWriter aiCallLogWriter;
    @Autowired AiCallLogRepository aiCallLogRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    MockRestServiceServer server;
    GptClient client;
    /** 이 테스트가 남긴 ai_call_log 행의 model 값 (50자 이하) */
    String model;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        model = "test-" + UUID.randomUUID();
        // 응답이 없는 시도(503 등)는 설정의 model 로 기록되므로 설정에도 같은 고유값을 넣는다
        OpenAiProperties properties = new OpenAiProperties("test-key", model, BASE_URL,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofSeconds(1));
        client = GptClientTestSupport.create(builder.build(), properties, aiCallLogWriter, objectMapper);
    }

    @AfterEach
    void cleanUp() {
        // 이 테스트가 만든 행만 지운다
        jdbc.update("delete from ai_call_log where model = ?", model);
    }

    GptRequest request() {
        return GptRequest.of(null, List.of(GptContentPart.text("요약해 줘")), "title", SCHEMA);
    }

    List<Map<String, Object>> newLogs() {
        return jdbc.queryForList("select purpose, model, status, input_tokens, cached_input_tokens, output_tokens,"
                + " latency_ms, error_message, created_at, updated_at from ai_call_log where model = ? order by log_id",
                model);
    }

    @Test
    void 시도마다_기록이_남는다() {
        server.expect(requestTo(BASE_URL + "/chat/completions")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE_URL + "/chat/completions")).andRespond(withSuccess(okBody(model), MediaType.APPLICATION_JSON));

        Title title = client.call(GptPurpose.COMPARE, request(), Title.class, GptContext.none());

        assertThat(title.title()).isEqualTo("근로계약서");
        List<Map<String, Object>> logs = newLogs();
        assertThat(logs).extracting(r -> r.get("status")).containsExactly("RETRY", "OK");
        assertThat(logs).extracting(r -> r.get("purpose")).containsOnly("COMPARE");
        assertThat(logs.get(0).get("error_message").toString()).contains("SERVER_ERROR").contains("503");
        assertThat(logs.get(1).get("input_tokens")).isEqualTo(11);
        assertThat(logs.get(1).get("cached_input_tokens")).isEqualTo(0);
        assertThat(logs.get(1).get("output_tokens")).isEqualTo(5);
        assertThat(logs.get(0).get("model")).isEqualTo(model);
        assertThat(logs.get(1).get("model")).isEqualTo(model);
        assertThat(logs.get(1).get("latency_ms")).isNotNull();
        assertThat(logs.get(1).get("created_at")).isNotNull();
        assertThat(logs.get(1).get("updated_at")).isNotNull();
    }

    @Test
    void 기록은_호출_쪽_트랜잭션이_롤백돼도_남는다() {
        String marker = "outer-tx-" + UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 바깥 트랜잭션의 쓰기: 롤백되면 사라져야 한다
            jdbc.update("insert into ai_call_log (purpose, model, status, error_message, created_at, updated_at)"
                    + " values ('CHAT', ?, 'FAILED', ?, now(), now())", model, marker);
            // 기록은 REQUIRES_NEW 로 먼저 커밋된다
            aiCallLogWriter.write(new AiCallLogCommand(null, GptPurpose.CHAT, null, null, null, null,
                    model, 1, 0, 1, 5, AiCallStatus.OK, null));
            status.setRollbackOnly();
        });

        assertThat(newLogs()).extracting(r -> r.get("status")).containsExactly("OK");
        assertThat(jdbc.queryForObject("select count(*) from ai_call_log where error_message = ?", Integer.class, marker))
                .isZero();
    }

    @Test
    void 호출_횟수는_RETRY를_빼고_호출마다_한_번씩_센다() {
        // ai_call_log.analysis_id 가 analysis 를 FK 로 가리키므로 분석 행까지 만든다. 끝나면 만든 행만 ID 로 지운다.
        // users, document_type, contract, contract_version 은 B 테이블이다. 지금은 B 의 생성 서비스가 없어
        // JDBC 로 직접 넣는다. B 서비스(사용자 발급, 계약서 등록)가 생기면 그걸로 바꿀 것.
        UUID userId = UUID.randomUUID();
        String typeCode = "TEST_AICALL_" + System.nanoTime();
        Long contractId = null;
        Long versionId = null;
        Long analysisId = null;
        try {
            jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                    userId, String.format("%064x", System.nanoTime()));
            jdbc.update("insert into document_type (code, name, category, situation, description, check_level,"
                    + " supported, popular, sort_order) values (?, 'test', 'TEST', 'test', 'test', 'A', false, false, 999)",
                    typeCode);
            contractId = jdbc.queryForObject("insert into contract (user_id, document_type_code, archived,"
                    + " current_version_no, created_at, updated_at) values (?, ?, false, 1, now(), now())"
                    + " returning contract_id", Long.class, userId, typeCode);
            versionId = jdbc.queryForObject("insert into contract_version (contract_id, version_no, page_count,"
                    + " created_at, updated_at) values (?, 1, 1, now(), now()) returning version_id",
                    Long.class, contractId);
            analysisId = jdbc.queryForObject("insert into analysis (version_id, document_type_code, status,"
                    + " progress_step, prompt_version, started_at) values (?, ?, 'ANALYZING', 'READING_TEXT', 'test', now())"
                    + " returning analysis_id", Long.class, versionId, typeCode);

            // JUDGE_MORE 1회 호출: 503 한 번 뒤 성공 → 기록 2행 (RETRY, OK), 호출은 1회
            server.expect(requestTo(BASE_URL + "/chat/completions")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
            server.expect(requestTo(BASE_URL + "/chat/completions")).andRespond(withSuccess(okBody(model), MediaType.APPLICATION_JSON));
            server.expect(requestTo(BASE_URL + "/chat/completions")).andRespond(withSuccess(okBody(model), MediaType.APPLICATION_JSON));
            client.call(GptPurpose.JUDGE_MORE, request(), Title.class, GptContext.analysis(userId, analysisId));
            // JUDGE 1회 호출 (다른 목적은 세지 않음)
            client.call(GptPurpose.JUDGE, request(), Title.class, GptContext.analysis(userId, analysisId));

            assertThat(jdbc.queryForObject("select count(*) from ai_call_log where analysis_id = ?",
                    Integer.class, analysisId)).isEqualTo(3);
            assertThat(aiCallLogRepository.countCalls(analysisId, GptPurpose.JUDGE_MORE)).isEqualTo(1);
            assertThat(aiCallLogRepository.countCalls(analysisId, GptPurpose.JUDGE)).isEqualTo(1);
            assertThat(aiCallLogRepository.countCalls(analysisId, GptPurpose.EXTRACT)).isZero();
        } finally {
            if (analysisId != null) {
                jdbc.update("delete from ai_call_log where analysis_id = ? and model = ?", analysisId, model);
                jdbc.update("delete from analysis where analysis_id = ?", analysisId);
            }
            if (versionId != null) {
                jdbc.update("delete from contract_version where version_id = ?", versionId);
            }
            if (contractId != null) {
                jdbc.update("delete from contract where contract_id = ?", contractId);
            }
            jdbc.update("delete from document_type where code = ?", typeCode);
            jdbc.update("delete from users where user_id = ?", userId);
        }
    }

    @Test
    void 트랜잭션_안에서_GPT를_부르면_막힌다() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                client.call(GptPurpose.CHAT, request(), Title.class, GptContext.none())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("트랜잭션 밖");

        server.verify();
        assertThat(newLogs()).isEmpty();
    }
}
