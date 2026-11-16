package com.team.docai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.client.*;
import com.team.docai.domain.analysis.service.AiCallLogWriter;
import com.team.docai.domain.contract.dto.CreateContractCommand;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.config.OpenAiProperties;
import com.team.docai.global.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="DOCAI_AUTH_INTEGRATION_TESTS",matches="true")
class ContractAnalysisConnectionTest {
    @Autowired ContractService contracts;
    @Autowired FileStorage storage;
    @Autowired AiCallLogWriter logWriter;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    public record Title(String title) {}

    @Test
    void savedImagePassesToGptClientAndCompletedAnalysisLinksInOneTransaction() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users(user_id,device_id_hash,created_at) VALUES (?,?,now())",
                user, UUID.randomUUID().toString().replace("-", "").repeat(2));
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", bytes);
        long id = contracts.create(user, UUID.randomUUID(),
                new CreateContractCommand("EMPLOYMENT", List.of(bytes.toByteArray()), List.of(0))).contractId();
        var tx = new TransactionTemplate(manager);
        var source = tx.execute(status -> {
            contracts.lockForUpdate(id);
            return contracts.getForAnalysis(user, id);
        });
        assertNotNull(source);
        Long analysisId = tx.execute(status -> {
            contracts.lockForUpdate(id);
            return jdbc.queryForObject("INSERT INTO analysis(version_id,document_type_code,status,progress_step,prompt_version,started_at) VALUES (?,'EMPLOYMENT','ANALYZING','READING_TEXT','connection-test',now()) RETURNING analysis_id",
                    Long.class, source.versionId());
        });
        String url = "https://gpt-connection.invalid";
        var builder = RestClient.builder().baseUrl(url);
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiProperties("test-only", "connection-test", url,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0,
                Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofSeconds(1));
        var client = GptClientTestSupport.create(builder.build(), properties, logWriter, mapper);
        var request = GptRequest.of(null,
                List.of(GptContentPart.image(storage.read(source.pages().get(0).imageKey()), "image/jpeg")),
                "title", Map.of("type", "object", "properties", Map.of("title", Map.of("type", "string")),
                        "required", List.of("title"), "additionalProperties", false));
        String response = mapper.writeValueAsString(Map.of(
                "model", "connection-test",
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message",
                        Map.of("role", "assistant", "content", "{\"title\":\"근로계약서\"}"))),
                "usage", Map.of("prompt_tokens", 11, "completion_tokens", 5)));
        server.expect(requestTo(url + "/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data:image/jpeg;base64,")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                client.call(GptPurpose.EXTRACT, request, Title.class, GptContext.analysis(user, analysisId))));
        Title title = client.call(GptPurpose.EXTRACT, request, Title.class, GptContext.analysis(user, analysisId));
        server.verify();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_call_log WHERE analysis_id=? AND status='OK'",
                Integer.class, analysisId));
        tx.executeWithoutResult(status -> {
            contracts.lockForUpdate(id);
            int changed = jdbc.update("UPDATE analysis SET status='COMPLETED',progress_step='DONE',completed_at=now() WHERE analysis_id=? AND status='ANALYZING'", analysisId);
            assertEquals(1, changed);
            contracts.linkLatestAnalysis(source.versionId(), analysisId);
            contracts.setTitleIfEmpty(id, title.title());
        });
        assertEquals(analysisId, jdbc.queryForObject("SELECT latest_analysis_id FROM contract_version WHERE version_id=?", Long.class, source.versionId()));
        assertEquals("근로계약서", jdbc.queryForObject("SELECT title FROM contract WHERE contract_id=?", String.class, id));
    }
}
