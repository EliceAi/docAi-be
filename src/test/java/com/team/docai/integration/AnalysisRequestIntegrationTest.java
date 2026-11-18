package com.team.docai.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.dto.AnalysisRequestInfo;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.dto.AnalysisRequestedEvent;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.service.AnalysisLiveness;
import com.team.docai.domain.analysis.service.AnalysisLivenessScheduler;
import com.team.docai.domain.analysis.service.AnalysisPipelineLauncher;
import com.team.docai.domain.analysis.service.AnalysisPipeline;
import com.team.docai.domain.analysis.service.AnalysisRequestService;
import com.team.docai.domain.analysis.service.AnalysisRequestWriter;
import com.team.docai.domain.analysis.service.AnalysisStepHandler;
import com.team.docai.domain.analysis.service.AnalysisWriter;
import com.team.docai.domain.analysis.service.RunningAnalysisRegistry;
import com.team.docai.domain.contract.dto.CreateContractCommand;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.global.auth.TokenService;
import com.team.docai.global.config.AnalysisAsyncConfig;
import com.team.docai.global.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 요청 API 와 실행 뼈대를 실제 DB(V2 스키마)와 B 의 실제 서비스로 확인한다: 202 응답과 저장 값, 404·409,
 * 중복·동시 요청에서 ANALYZING 하나, 커밋 후에만 파이프라인 시작, heartbeat 갱신, 멈춘 분석 FAILED, 단계 예외 FAILED,
 * 정상 완료(최신 분석 연결), 스레드 풀 거절.
 * <ul>
 *   <li>계약서는 B 의 실제 등록 경로(ContractService.create)로 만든다 (이미지 파일도 B 의 FileStorage 에 저장됨).</li>
 *   <li>ContractService·NotificationService 는 실제 빈을 감싼 스파이다. 호출은 실제 메서드로 가고, 테스트는 호출 여부만 본다.
 *       UNIQUE 위반 재현 테스트만 잠금을 끄고(doNothing) 두 요청이 조회 뒤 함께 출발하게 한다.
 *       스파이는 트랜잭션 프록시 안쪽에 있으므로 stub·verify 는 프록시를 벗긴 대상(AopTestUtils)에 한다
 *       (프록시로 verify 하면 MANDATORY 메서드가 트랜잭션 없음 예외를 던진다).</li>
 *   <li>실행 뼈대만 보려고 실제 1차 단계(ExtractStep)는 끄고(docai.analysis.extract.enabled=false, GPT 를 부르지 않음)
 *       가짜 단계 하나(멈춤·예외·통과)를 꽂는다. 1차 단계는 ExtractPipelineIntegrationTest 가 본다.</li>
 *   <li>스케줄러는 테스트 설정(config/application.yml)으로 꺼져 있다. 같은 DB 의 다른 행을 바꾸지 않도록
 *       멈춘 분석 정리는 이 테스트가 만든 분석 ID 로만 부른다.</li>
 *   <li>이 테스트가 만든 행(users, idempotency_record, contract, contract_version, contract_page, analysis, analysis_chunk,
 *       notification)과 이미지 파일만 ID 로 지운다. document_type 등 운영 데이터는 읽기만 한다.</li>
 * </ul>
 */
@SpringBootTest(properties = "docai.analysis.extract.enabled=false")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class AnalysisRequestIntegrationTest {

    static final String TYPE = "EMPLOYMENT";

    enum Mode { BLOCK, THROW, PASS }

    /** 가짜 분석 단계. BLOCK 이면 release 될 때까지 기다린다 (GPT 응답을 기다리는 단계 흉내) */
    static class FakeStep implements AnalysisStepHandler {
        volatile Mode mode = Mode.BLOCK;
        volatile CountDownLatch release = new CountDownLatch(1);
        final List<Long> entered = new CopyOnWriteArrayList<>();

        @Override
        public AnalysisStep progressStep() {
            return AnalysisStep.FINDING_TERMS;
        }

        @Override
        public void execute(AnalysisRunContext context) {
            entered.add(context.analysisId());
            switch (mode) {
                case THROW -> throw new IllegalStateException("가짜 단계 오류");
                case BLOCK -> {
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                case PASS -> { }
            }
        }
    }

    @TestConfiguration
    static class FakeStepConfig {
        @Bean FakeStep fakeStep() { return new FakeStep(); }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TokenService tokenService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AnalysisRequestService requestService;
    @Autowired AnalysisRequestWriter requestWriter;
    @Autowired AnalysisWriter analysisWriter;
    @Autowired AnalysisLiveness livenessScheduler;
    @Autowired AnalysisRepository analysisRepository;
    @Autowired ApplicationContext context;
    @Autowired RunningAnalysisRegistry registry;
    @Autowired FileStorage storage;
    @Value("${docai.storage.root:./storage}") String storageRoot;
    @Autowired FakeStep fakeStep;
    @MockitoSpyBean AnalysisPipeline pipeline;
    @MockitoSpyBean ContractService contractService;
    @MockitoSpyBean NotificationService notificationService;
    @MockitoSpyBean(name = AnalysisAsyncConfig.ANALYSIS_EXECUTOR) ThreadPoolTaskExecutor analysisExecutor;

    /** 트랜잭션 프록시를 벗긴 스파이 (stub·verify 용) */
    ContractService contractSpy;
    NotificationService notificationSpy;

    UUID userId;
    final List<Long> contractIds = new ArrayList<>();
    final List<Long> versionIds = new ArrayList<>();
    final List<UUID> userIds = new ArrayList<>();
    final List<String> imageKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fakeStep.mode = Mode.BLOCK;
        fakeStep.release = new CountDownLatch(1);
        contractSpy = AopTestUtils.getUltimateTargetObject(contractService);
        notificationSpy = AopTestUtils.getUltimateTargetObject(notificationService);
        assertThat(mockingDetails(contractSpy).isSpy()).isTrue();
        assertThat(mockingDetails(notificationSpy).isSpy()).isTrue();
        userId = newUser();
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        fakeStep.release.countDown();
        // 이 테스트의 분석 파이프라인이 끝날 때까지 기다린 뒤 지운다 (도는 중에 지우면 FK 오류)
        for (int i = 0; i < 100 && countAnalyzing() > 0; i++) {
            Thread.sleep(100);
        }
        for (UUID id : userIds) {
            jdbc.update("delete from notification where user_id = ?", id);
        }
        for (Long versionId : versionIds) {
            jdbc.update("update contract_version set latest_analysis_id = null where version_id = ?", versionId);
            jdbc.update("delete from analysis_chunk where analysis_id in (select analysis_id from analysis where version_id = ?)", versionId);
            jdbc.update("delete from analysis where version_id = ?", versionId);
            jdbc.update("delete from contract_page where version_id = ?", versionId);
            jdbc.update("delete from contract_version where version_id = ?", versionId);
        }
        for (Long contractId : contractIds) {
            jdbc.update("delete from contract where contract_id = ?", contractId);
        }
        for (UUID id : userIds) {
            jdbc.update("delete from idempotency_record where user_id = ?", id);
            jdbc.update("delete from users where user_id = ?", id);
        }
        for (String key : imageKeys) {
            storage.delete(key);
        }
        // B 의 FileStorage 가 사용자마다 만든 폴더 (이 테스트 사용자 것만, 비어 있을 때만)
        for (UUID id : userIds) {
            try {
                Files.deleteIfExists(Path.of(storageRoot).toAbsolutePath().normalize().resolve("contracts").resolve(id.toString()));
            } catch (IOException e) {
                // 다른 파일이 남아 있으면 지우지 않는다
            }
        }
    }

    UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                id, String.format("%064x", System.nanoTime()));
        userIds.add(id);
        return id;
    }

    static byte[] png() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** B 의 등록 경로(ContractService.create)로 계약서 + 버전 1 + 쪽 pageCount 개를 만든다. [contractId, versionId] */
    long[] newContract(UUID owner, int pageCount, boolean archived) {
        byte[] image = png();
        long contractId = contractService.create(owner, UUID.randomUUID(), new CreateContractCommand(TYPE,
                Collections.nCopies(pageCount, image), Collections.nCopies(pageCount, 0))).contractId();
        contractIds.add(contractId);
        var info = contractService.getForAnalysis(owner, contractId);
        versionIds.add(info.versionId());
        info.pages().forEach(p -> imageKeys.add(p.imageKey()));
        if (archived) {
            // B 의 보관 API 가 아직 없어 이 테스트의 계약서만 직접 보관 상태로 바꾼다
            jdbc.update("update contract set archived = true, archived_at = now() where contract_id = ?", contractId);
        }
        return new long[]{contractId, info.versionId()};
    }

    Long latestAnalysisId(long versionId) {
        return jdbc.queryForObject("select latest_analysis_id from contract_version where version_id = ?", Long.class, versionId);
    }

    long countAnalyzing() {
        if (versionIds.isEmpty()) {
            return 0;
        }
        String in = String.join(",", Collections.nCopies(versionIds.size(), "?"));
        return jdbc.queryForObject("select count(*) from analysis where status = 'ANALYZING' and version_id in (" + in + ")",
                Long.class, versionIds.toArray());
    }

    long countAnalyzing(long versionId) {
        return jdbc.queryForObject("select count(*) from analysis where version_id = ? and status = 'ANALYZING'",
                Long.class, versionId);
    }

    Map<String, Object> analysisRow(long analysisId) {
        return jdbc.queryForMap("select * from analysis where analysis_id = ?", analysisId);
    }

    String statusOf(long analysisId) {
        return jdbc.queryForObject("select status from analysis where analysis_id = ?", String.class, analysisId);
    }

    void awaitStatus(long analysisId, String expected) throws InterruptedException {
        for (int i = 0; i < 100 && !expected.equals(statusOf(analysisId)); i++) {
            Thread.sleep(100);
        }
        assertThat(statusOf(analysisId)).isEqualTo(expected);
    }

    void awaitEntered(long analysisId) throws InterruptedException {
        for (int i = 0; i < 100 && !fakeStep.entered.contains(analysisId); i++) {
            Thread.sleep(50);
        }
        assertThat(fakeStep.entered).contains(analysisId);
    }

    long postAnalysis(UUID user, long contractId) throws Exception {
        String body = mvc.perform(post("/api/contracts/{id}/analyses", contractId)
                        .header("Authorization", "Bearer " + tokenService.issue(user)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = mapper.readTree(body).get("data");
        return data.get("analysisId").asLong();
    }

    @Test
    void 요청하면_202이고_분석과_쪽_묶음을_저장하며_최신_분석은_바꾸지_않는다() throws Exception {
        long[] c = newContract(userId, 7, false);
        org.mockito.Mockito.clearInvocations(contractSpy);

        mvc.perform(post("/api/contracts/{id}/analyses", c[0]).header("Authorization", "Bearer " + tokenService.issue(userId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value(202))
                .andExpect(jsonPath("$.data.contractId").value(c[0]))
                .andExpect(jsonPath("$.data.status").value("ANALYZING"))
                .andExpect(jsonPath("$.data.progressStep").value("READING_TEXT"))
                .andExpect(jsonPath("$.data.readProgress.done").value(0))
                .andExpect(jsonPath("$.data.readProgress.total").value(2));

        Long analysisId = jdbc.queryForObject("select analysis_id from analysis where version_id = ?", Long.class, c[1]);
        Map<String, Object> row = analysisRow(analysisId);
        assertThat(row.get("status")).isEqualTo("ANALYZING");
        assertThat(row.get("document_type_code")).isEqualTo(TYPE);
        assertThat(row.get("prompt_version")).isEqualTo("extract-v3/judge-v1");
        assertThat(row.get("started_at")).isNotNull();
        assertThat(row.get("heartbeat_at")).isNotNull();
        assertThat(row.get("completed_at")).isNull();
        assertThat(jdbc.queryForList("select page_from || '-' || page_to || ':' || status from analysis_chunk"
                + " where analysis_id = ? order by chunk_no", String.class, analysisId))
                .containsExactly("1-5:PENDING", "6-7:PENDING");
        assertThat(latestAnalysisId(c[1])).isNull();
        // 소유권 확인 → B 의 잠금(MANDATORY, 같은 요청 트랜잭션) → 잠근 뒤 다시 조회
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(contractSpy);
        order.verify(contractSpy).getForAnalysis(userId, c[0]);
        order.verify(contractSpy).lockForUpdate(c[0]);
        order.verify(contractSpy).getForAnalysis(userId, c[0]);
        awaitEntered(analysisId);
    }

    @Test
    void 남의_계약서와_없는_계약서는_404_보관된_계약서는_409() throws Exception {
        long[] others = newContract(newUser(), 1, false);
        long[] deleted = newContract(userId, 1, false);
        jdbc.update("update contract set deleted_at = now() where contract_id = ?", deleted[0]);
        long[] archived = newContract(userId, 1, true);
        String token = "Bearer " + tokenService.issue(userId);

        mvc.perform(post("/api/contracts/{id}/analyses", others[0]).header("Authorization", token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
        mvc.perform(post("/api/contracts/{id}/analyses", Long.MAX_VALUE).header("Authorization", token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
        mvc.perform(post("/api/contracts/{id}/analyses", deleted[0]).header("Authorization", token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
        mvc.perform(post("/api/contracts/{id}/analyses", archived[0]).header("Authorization", token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTRACT_ARCHIVED"));
        assertThat(jdbc.queryForObject("select count(*) from analysis where version_id in (?, ?, ?)",
                Long.class, others[1], archived[1], deleted[1])).isZero();
    }

    @Test
    void 분석_중에_다시_요청하면_같은_분석을_돌려준다() throws Exception {
        long[] c = newContract(userId, 2, false);

        long first = postAnalysis(userId, c[0]);
        awaitEntered(first);
        long second = postAnalysis(userId, c[0]);

        assertThat(second).isEqualTo(first);
        assertThat(countAnalyzing(c[1])).isEqualTo(1);
    }

    @Test
    void 동시_요청_두_개도_계약서_잠금으로_ANALYZING은_하나() throws Exception {
        long[] c = newContract(userId, 2, false);

        List<Long> ids = runTwoConcurrently(c[0]);

        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(countAnalyzing(c[1])).isEqualTo(1);
    }

    @Test
    void 잠금이_없어_UNIQUE_인덱스에_걸려도_500이_아니라_먼저_만든_분석을_준다() throws Exception {
        long[] c = newContract(userId, 2, false);
        // 계약서 잠금을 끄고, 두 요청이 B 의 실제 조회를 마친 뒤 함께 출발하게 한다
        doNothing().when(contractSpy).lockForUpdate(anyLong());
        CyclicBarrier barrier = new CyclicBarrier(2);
        // 요청마다 getForAnalysis 를 두 번 부른다 (소유권 확인, 잠근 뒤 다시 조회). 두 번째 조회 뒤에 함께 출발시킨다
        ThreadLocal<Integer> callsInThread = ThreadLocal.withInitial(() -> 0);
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            callsInThread.set(callsInThread.get() + 1);
            if (callsInThread.get() == 2) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (BrokenBarrierException | TimeoutException e) {
                    throw new IllegalStateException(e);
                }
            }
            return result;
        }).when(contractSpy).getForAnalysis(any(), anyLong());

        List<Long> ids = runTwoConcurrently(c[0]);

        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(countAnalyzing(c[1])).isEqualTo(1);
    }

    List<Long> runTwoConcurrently(long contractId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AnalysisRequestInfo>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return requestService.request(userId, contractId);
                }));
            }
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<AnalysisRequestInfo> f : futures) {
                ids.add(f.get(20, TimeUnit.SECONDS).analysisId());
            }
            return ids;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 커밋되어야_파이프라인이_시작되고_롤백되면_시작하지_않는다() throws Exception {
        long[] rolledBack = newContract(userId, 1, false);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Long rolledBackId = tx.execute(status -> {
            Long id = requestWriter.createOrGetRunning(userId, rolledBack[0]).analysisId();
            status.setRollbackOnly();
            return id;
        });

        verify(pipeline, after(1500).never()).run(argThat(ctx -> ctx.analysisId().equals(rolledBackId)));
        assertThat(jdbc.queryForObject("select count(*) from analysis where analysis_id = ?", Long.class, rolledBackId)).isZero();

        long[] committed = newContract(userId, 1, false);
        Long committedId = tx.execute(status -> {
            Long id = requestWriter.createOrGetRunning(userId, committed[0]).analysisId();
            // 커밋 전에는 파이프라인이 시작되지 않는다
            verify(pipeline, after(300).never()).run(argThat(ctx -> ctx.analysisId().equals(id)));
            return id;
        });
        verify(pipeline, timeout(5000)).run(argThat(ctx -> ctx.analysisId().equals(committedId)));
    }

    @Test
    void 단계가_GPT를_기다리는_동안에도_heartbeat가_갱신된다() throws Exception {
        long[] c = newContract(userId, 1, false);
        long analysisId = postAnalysis(userId, c[0]);
        awaitEntered(analysisId);
        assertThat(registry.isRunning(analysisId)).isTrue();
        jdbc.update("update analysis set heartbeat_at = now() - interval '10 minutes' where analysis_id = ?", analysisId);
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(5);

        livenessScheduler.heartbeat();

        OffsetDateTime heartbeat = jdbc.queryForObject("select heartbeat_at from analysis where analysis_id = ?",
                OffsetDateTime.class, analysisId);
        assertThat(heartbeat).isAfter(before);
        assertThat(analysisRow(analysisId).get("progress_step")).isEqualTo("FINDING_TERMS");
    }

    @Test
    void heartbeat가_2분_넘게_멈춘_분석만_FAILED_TEMPORARY_ERROR() throws Exception {
        long[] c = newContract(userId, 1, false);
        long analysisId = postAnalysis(userId, c[0]);
        awaitEntered(analysisId);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);

        // 아직 살아 있음 (방금 갱신)
        assertThat(analysisWriter.findStaleIds(cutoff)).doesNotContain(analysisId);
        assertThat(analysisWriter.failStale(analysisId, cutoff)).isFalse();

        jdbc.update("update analysis set heartbeat_at = now() - interval '3 minutes' where analysis_id = ?", analysisId);
        assertThat(analysisWriter.findStaleIds(cutoff)).contains(analysisId);
        assertThat(analysisWriter.failStale(analysisId, cutoff)).isTrue();

        Map<String, Object> row = analysisRow(analysisId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("fail_reason")).isEqualTo("TEMPORARY_ERROR");
        assertThat(row.get("progress_step")).isEqualTo("FINDING_TERMS");
        assertThat(row.get("completed_at")).isNotNull();

        // 늦게 끝난 파이프라인은 FAILED 를 COMPLETED 로 덮어쓰지 않는다
        fakeStep.release.countDown();
        for (int i = 0; i < 50 && registry.isRunning(analysisId); i++) {
            Thread.sleep(100);
        }
        assertThat(statusOf(analysisId)).isEqualTo("FAILED");
        assertThat(latestAnalysisId(c[1])).isNull();
        verify(contractSpy, never()).linkLatestAnalysis(anyLong(), eq(analysisId));
        verify(notificationSpy, never()).createAnalysisResult(any(), eq(analysisId), any(), any(), any());
    }

    @Test
    void 단계에서_예외가_나면_FAILED_TEMPORARY_ERROR와_실패_알림() throws Exception {
        fakeStep.mode = Mode.THROW;
        long[] c = newContract(userId, 1, false);

        long analysisId = postAnalysis(userId, c[0]);

        awaitStatus(analysisId, "FAILED");
        assertThat(analysisRow(analysisId).get("fail_reason")).isEqualTo("TEMPORARY_ERROR");
        // 실제 NotificationService 가 FAILED 전환과 같은 트랜잭션에서 불렸다
        verify(notificationSpy).createAnalysisResult(eq(userId), eq(analysisId), eq("ANALYSIS_FAILED"), any(), any());
        verify(contractSpy, never()).linkLatestAnalysis(anyLong(), eq(analysisId));
        assertThat(latestAnalysisId(c[1])).isNull();
    }

    @Test
    void 단계가_모두_끝나면_COMPLETED_최신_분석_연결_완료_알림() throws Exception {
        fakeStep.mode = Mode.PASS;
        long[] c = newContract(userId, 1, false);

        long analysisId = postAnalysis(userId, c[0]);

        awaitStatus(analysisId, "COMPLETED");
        assertThat(analysisRow(analysisId).get("progress_step")).isEqualTo("DONE");
        // 실제 ContractService.linkLatestAnalysis 가 COMPLETED 와 같은 트랜잭션에서 최신 분석을 연결했다
        assertThat(latestAnalysisId(c[1])).isEqualTo(analysisId);
        verify(notificationSpy).createAnalysisResult(eq(userId), eq(analysisId), eq("ANALYSIS_DONE"), any(), any());
    }

    /** 이 테스트의 버전에 ANALYZING 분석을 만들고 (파이프라인은 시작하지 않음) 실행 맥락을 돌려준다 */
    AnalysisRunContext newAnalyzing() {
        long[] c = newContract(userId, 1, false);
        Analysis saved = analysisRepository.save(Analysis.create(c[1], TYPE, "extract-v1/judge-v1", OffsetDateTime.now()));
        return new AnalysisRunContext(saved.getAnalysisId(), userId, c[0], c[1], TYPE);
    }

    @Test
    void 테스트_설정은_분석_스케줄_빈만_끈다() {
        assertThat(context.getBeansOfType(AnalysisLivenessScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(AnalysisLiveness.class)).hasSize(1);
        // analysisExecutor 빈이 있어 Boot 기본 applicationTaskExecutor 는 만들어지지 않는다 (AnalysisAsyncConfig javadoc)
        assertThat(context.containsBean("applicationTaskExecutor")).isFalse();
        // @EnableScheduling 은 꺼지지 않는다 (앱의 다른 @Scheduled 용)
        assertThat(context.getBeanNamesForType(
                org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
    }

    @Test
    void 대기열에서_기다리는_동안에도_heartbeat가_갱신되고_차례가_오면_돈다() throws Exception {
        fakeStep.mode = Mode.PASS;
        AnalysisRunContext ctx = newAnalyzing();
        List<Runnable> queued = new CopyOnWriteArrayList<>();
        Executor queueing = queued::add;
        AnalysisPipelineLauncher launcher = new AnalysisPipelineLauncher(pipeline, registry, analysisWriter, queueing);

        launcher.onRequested(new AnalysisRequestedEvent(ctx));

        assertThat(queued).hasSize(1);
        assertThat(registry.isRunning(ctx.analysisId())).isTrue();
        jdbc.update("update analysis set heartbeat_at = now() - interval '10 minutes' where analysis_id = ?", ctx.analysisId());
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(5);
        livenessScheduler.heartbeat();
        assertThat(jdbc.queryForObject("select heartbeat_at from analysis where analysis_id = ?",
                OffsetDateTime.class, ctx.analysisId())).isAfter(before);
        assertThat(analysisWriter.findStaleIds(OffsetDateTime.now().minusMinutes(2))).doesNotContain(ctx.analysisId());

        queued.get(0).run();

        assertThat(statusOf(ctx.analysisId())).isEqualTo("COMPLETED");
        assertThat(registry.isRunning(ctx.analysisId())).isFalse();
    }

    @Test
    void 커밋_후_스레드_풀이_거절하면_FAILED_TEMPORARY_ERROR가_커밋되고_실패_알림() {
        long[] c = newContract(userId, 1, false);
        doThrow(new TaskRejectedException("가득 참")).when(analysisExecutor).execute(any(Runnable.class));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // 요청 트랜잭션 커밋 → AFTER_COMMIT 리스너(실제 Launcher 빈) → 거절 → 새 트랜잭션으로 FAILED 커밋
        Long analysisId = tx.execute(status -> requestWriter.createOrGetRunning(userId, c[0]).analysisId());

        Map<String, Object> row = analysisRow(analysisId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("fail_reason")).isEqualTo("TEMPORARY_ERROR");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(registry.isRunning(analysisId)).isFalse();
        verify(pipeline, never()).run(argThat(ctx -> ctx.analysisId().equals(analysisId)));
        verify(notificationSpy).createAnalysisResult(eq(userId), eq(analysisId), eq("ANALYSIS_FAILED"), any(), any());
        assertThat(latestAnalysisId(c[1])).isNull();
    }

    @Test
    void 분석_중에_계약서가_삭제되면_완료가_롤백되고_FAILED와_실패_알림이며_최신_분석은_연결되지_않는다() throws Exception {
        long[] c = newContract(userId, 1, false);
        long analysisId = postAnalysis(userId, c[0]);
        awaitEntered(analysisId);

        // 단계가 GPT 를 기다리는 동안 계약서가 삭제됨 (B 의 정리 배치·폐기와 같은 상태)
        jdbc.update("update contract set deleted_at = now() where contract_id = ?", c[0]);
        fakeStep.mode = Mode.PASS;
        fakeStep.release.countDown();

        // COMPLETED 전환 → B3 linkLatestAnalysis 가 CONTRACT_NOT_FOUND → 같은 트랜잭션 롤백 → 파이프라인이 FAILED
        awaitStatus(analysisId, "FAILED");
        Map<String, Object> row = analysisRow(analysisId);
        assertThat(row.get("fail_reason")).isEqualTo("TEMPORARY_ERROR");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(latestAnalysisId(c[1])).isNull();
        verify(contractSpy).linkLatestAnalysis(c[1], analysisId);
        verify(notificationSpy, never()).createAnalysisResult(any(), eq(analysisId), eq("ANALYSIS_DONE"), any(), any());
        verify(notificationSpy).createAnalysisResult(eq(userId), eq(analysisId), eq("ANALYSIS_FAILED"), any(), any());
    }

    @Test
    void 토큰이_없거나_잘못되면_401이고_분석을_만들지_않는다() throws Exception {
        long[] c = newContract(userId, 1, false);

        mvc.perform(post("/api/contracts/{id}/analyses", c[0]))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/contracts/{id}/analyses", c[0]).header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(jdbc.queryForObject("select count(*) from analysis where version_id = ?", Long.class, c[1])).isZero();
    }

    @Test
    void 한_사용자가_1분에_설정_횟수를_넘게_요청하면_429_RATE_LIMITED() throws Exception {
        UUID user = newUser();
        long[] c = newContract(user, 1, false);
        int limit = context.getBean(com.team.docai.global.config.AnalysisProperties.class).requestsPerMinute();
        String token = "Bearer " + tokenService.issue(user);

        for (int i = 0; i < limit; i++) {
            mvc.perform(post("/api/contracts/{id}/analyses", c[0]).header("Authorization", token))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(post("/api/contracts/{id}/analyses", c[0]).header("Authorization", token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // 넘친 요청은 분석을 더 만들지 않는다 (분석 중 재요청은 같은 분석을 돌려줌)
        assertThat(countAnalyzing(c[1])).isEqualTo(1);
    }
}
