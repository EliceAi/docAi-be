package com.team.docai.domain.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptContentPart;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptFailureReason;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.dto.ExtractResponse;
import com.team.docai.domain.analysis.entity.AnalysisChunk;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.contract.dto.ContractForAnalysisInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.law.dto.IssueTagInfo;
import com.team.docai.domain.law.service.LawSearchService;
import com.team.docai.global.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.team.docai.domain.analysis.service.ExtractFixtures.clause;
import static com.team.docai.domain.analysis.service.ExtractFixtures.response;
import static com.team.docai.domain.analysis.service.ExtractFixtures.withType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1차 단계 (GPT·B·저장은 가짜). 묶음 병렬, 내용 오류 재호출, 실패 사유, 사진 읽기 범위 */
class ExtractStepTest {

    static final UUID USER = UUID.randomUUID();
    static final com.team.docai.global.config.OpenAiProperties GPT55 = new com.team.docai.global.config.OpenAiProperties(
            "k", "gpt-5.5", "https://x.test", java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), 1,
            java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
    static final AnalysisRunContext CTX = new AnalysisRunContext(88L, USER, 12L, 30L, "EMPLOYMENT");

    ContractService contractService = mock(ContractService.class);
    FileStorage fileStorage = mock(FileStorage.class);
    LawSearchService lawSearchService = mock(LawSearchService.class);
    GptClient gptClient = mock(GptClient.class);
    ExtractWriter writer = mock(ExtractWriter.class);
    ExecutorService pool;
    ExtractStep step;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
        step = new ExtractStep(contractService, fileStorage, lawSearchService, gptClient, writer, new ObjectMapper(),
                pool, Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneId.of("Asia/Seoul")), GPT55);
        when(lawSearchService.findIssueTags("EMPLOYMENT")).thenReturn(List.of(new IssueTagInfo("PENALTY_ON_RESIGNATION", "위약금")));
        when(fileStorage.read(anyString())).thenAnswer(inv -> ("img:" + inv.getArgument(0)).getBytes());
        when(writer.saveMerged(anyLong(), any())).thenReturn(true);
        when(writer.toJson(any())).thenReturn("{}");
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    void givenContract(int pages, long versionId) {
        List<ContractForAnalysisInfo.PageInfo> infos = IntStream.rangeClosed(1, pages)
                .mapToObj(i -> new ContractForAnalysisInfo.PageInfo(i, "contracts/u/" + i + ".jpg", null)).toList();
        when(contractService.getForAnalysis(USER, 12L))
                .thenReturn(new ContractForAnalysisInfo(12L, versionId, "EMPLOYMENT", false, infos));
    }

    static AnalysisChunk chunk(long id, int no, int from, int to) {
        AnalysisChunk c = AnalysisChunk.create(88L, no, from, to);
        ReflectionTestUtils.setField(c, "chunkId", id);
        return c;
    }

    void givenChunks(AnalysisChunk... chunks) {
        when(writer.findChunks(88L)).thenReturn(List.of(chunks));
    }

    ExtractResponse ok(int from, int to) {
        return response(from, to, List.of(clause("c1", from, "제" + from + "조", "조항" + from, "원문" + from)), List.of());
    }

    @Test
    void 진행_단계는_READING_TEXT() {
        assertThat(step.progressStep()).isEqualTo(AnalysisStep.READING_TEXT);
    }

    @Test
    void 묶음마다_그_쪽_사진만_읽어_동시에_부르고_결과를_저장한_뒤_병합_저장한다() {
        givenContract(7, 30L);
        givenChunks(chunk(1L, 1, 1, 5), chunk(2L, 2, 6, 7));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        List<GptRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(gptClient.call(eq(GptPurpose.EXTRACT), any(), eq(ExtractResponse.class), any())).thenAnswer(inv -> {
            GptRequest request = inv.getArgument(1);
            requests.add(request);
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Thread.sleep(200);
            inFlight.decrementAndGet();
            GptContext ctx = inv.getArgument(3);
            return ctx.chunkId() == 1L ? ok(1, 5) : ok(6, 7);
        });

        step.execute(CTX);

        assertThat(maxInFlight.get()).isEqualTo(2);
        IntStream.rangeClosed(1, 7).forEach(i -> verify(fileStorage).read("contracts/u/" + i + ".jpg"));
        verify(writer).completeChunk(1L, "{}", 0);
        verify(writer).completeChunk(2L, "{}", 0);
        GptRequest second = requests.stream().filter(r -> r.userContent().size() == 5).findFirst().orElseThrow();
        assertThat(second.userContent().get(1)).isEqualTo(GptContentPart.text("6쪽"));
        assertThat(second.userContent().get(2)).isInstanceOf(GptContentPart.Image.class);
        assertThat(((GptContentPart.Image) second.userContent().get(2)).mimeType()).isEqualTo("image/jpeg");
        assertThat(second.systemPrompt()).contains("PENALTY_ON_RESIGNATION").contains("임금 지급일");
        ArgumentCaptor<ExtractMerger.Result> merged = ArgumentCaptor.forClass(ExtractMerger.Result.class);
        verify(writer).saveMerged(eq(88L), merged.capture());
        assertThat(merged.getValue().clauses()).extracting(ExtractMerger.MergedClause::clauseKey).containsExactly("c1", "c2");
        assertThat(merged.getValue().lawBaseDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 18));
    }

    @Test
    void 기록용_맥락에_분석과_묶음_ID를_넣는다() {
        givenContract(1, 30L);
        givenChunks(chunk(5L, 1, 1, 1));
        when(gptClient.call(any(), any(), any(), any())).thenReturn(ok(1, 1));

        step.execute(CTX);

        verify(gptClient).call(eq(GptPurpose.EXTRACT), any(), eq(ExtractResponse.class), eq(GptContext.chunk(USER, 88L, 5L)));
    }

    @Test
    void 응답_내용_오류는_그_묶음만_1회_다시_부르고_retry_count를_남긴다() {
        givenContract(1, 30L);
        givenChunks(chunk(1L, 1, 1, 1));
        when(gptClient.call(any(), any(), any(), any()))
                .thenThrow(new GptCallException(GptFailureReason.SCHEMA_MISMATCH, "x", 1))
                .thenReturn(ok(1, 1));

        step.execute(CTX);

        verify(gptClient, times(2)).call(any(), any(), any(), any());
        verify(writer).completeChunk(1L, "{}", 1);
    }

    @Test
    void 내용_오류가_두_번이면_묶음_FAILED_후_GPT_예외를_올린다() {
        givenContract(1, 30L);
        givenChunks(chunk(1L, 1, 1, 1));
        when(gptClient.call(any(), any(), any(), any()))
                .thenThrow(new GptCallException(GptFailureReason.INVALID_JSON, "x", 1));

        assertThatThrownBy(() -> step.execute(CTX)).isInstanceOf(GptCallException.class);

        verify(gptClient, times(2)).call(any(), any(), any(), any());
        verify(writer).failChunk(1L, 1);
        verify(writer, never()).saveMerged(anyLong(), any());
    }

    @Test
    void 통신_오류는_다시_부르지_않고_다른_묶음이_끝난_뒤_실패한다() {
        givenContract(7, 30L);
        givenChunks(chunk(1L, 1, 1, 5), chunk(2L, 2, 6, 7));
        when(gptClient.call(any(), any(), any(), any())).thenAnswer(inv -> {
            GptContext ctx = inv.getArgument(3);
            if (ctx.chunkId() == 1L) {
                throw new GptCallException(GptFailureReason.TIMEOUT, "x", 2);
            }
            Thread.sleep(100);
            return ok(6, 7);
        });

        assertThatThrownBy(() -> step.execute(CTX)).isInstanceOf(GptCallException.class)
                .extracting(e -> ((GptCallException) e).getReason()).isEqualTo(GptFailureReason.TIMEOUT);

        verify(writer).failChunk(1L, 0);
        verify(writer).completeChunk(2L, "{}", 0);
        verify(gptClient, times(2)).call(any(), any(), any(), any());
    }

    @Test
    void 다른_종류면_TYPE_MISMATCH로_끝내고_저장하지_않는다() {
        givenContract(1, 30L);
        givenChunks(chunk(1L, 1, 1, 1));
        when(gptClient.call(any(), any(), any(), any()))
                .thenReturn(withType(ok(1, 1), new ExtractResponse.TypeCheck(false, "영수증")));

        assertThatThrownBy(() -> step.execute(CTX)).isInstanceOf(AnalysisFailedException.class)
                .satisfies(e -> {
                    AnalysisFailedException f = (AnalysisFailedException) e;
                    assertThat(f.getReason()).isEqualTo(AnalysisFailReason.TYPE_MISMATCH);
                    assertThat(f.getDetectedTypeName()).isEqualTo("영수증");
                });
        verify(writer, never()).saveMerged(anyLong(), any());
    }

    @Test
    void 못_읽은_쪽이면_PAGE_UNREADABLE과_쪽_번호() {
        givenContract(2, 30L);
        givenChunks(chunk(1L, 1, 1, 2));
        when(gptClient.call(any(), any(), any(), any())).thenReturn(ExtractFixtures.withPages(ok(1, 2), List.of(
                new ExtractResponse.PageRead(1, "READ"), new ExtractResponse.PageRead(2, "UNREADABLE"))));

        assertThatThrownBy(() -> step.execute(CTX)).isInstanceOf(AnalysisFailedException.class)
                .satisfies(e -> {
                    assertThat(((AnalysisFailedException) e).getReason()).isEqualTo(AnalysisFailReason.PAGE_UNREADABLE);
                    assertThat(((AnalysisFailedException) e).getFailPageNo()).isEqualTo(2);
                });
    }

    @Test
    void 이미_DONE인_묶음은_저장된_결과를_다시_쓰고_GPT를_부르지_않는다() throws Exception {
        givenContract(1, 30L);
        AnalysisChunk done = chunk(1L, 1, 1, 1);
        done.complete(new ObjectMapper().writeValueAsString(ok(1, 1)), 0);
        givenChunks(done);

        step.execute(CTX);

        verify(gptClient, never()).call(any(), any(), any(), any());
        verify(fileStorage, never()).read(anyString());
        verify(writer).saveMerged(eq(88L), any());
    }

    @Test
    void 분석_중에_버전이_바뀌었으면_GPT를_부르지_않고_실패한다() {
        givenContract(1, 31L);

        assertThatThrownBy(() -> step.execute(CTX)).isInstanceOf(IllegalStateException.class);
        verify(gptClient, never()).call(any(), any(), any(), any());
    }

    @Test
    void 병합_저장_전에_분석이_끝났으면_조용히_멈춘다() {
        givenContract(1, 30L);
        givenChunks(chunk(1L, 1, 1, 1));
        when(gptClient.call(any(), any(), any(), any())).thenReturn(ok(1, 1));
        when(writer.saveMerged(anyLong(), any())).thenReturn(false);

        step.execute(CTX);

        verify(writer).saveMerged(eq(88L), any());
        verify(writer, never()).failChunk(anyLong(), anyInt());
    }

    @Test
    void 이미지는_분석_로그_문자열에_바이트로_남지_않는다() {
        ExtractPrompt.PageImage image = new ExtractPrompt.PageImage(1, new byte[]{1, 2, 3}, "image/jpeg");
        assertThat(image.toString()).isEqualTo("PageImage[pageNo=1, bytes=3]");
        List<GptContentPart> parts = new ArrayList<>(ExtractPrompt.userContent(1, 1, 1, List.of(image)));
        assertThat(parts.get(2).toString()).doesNotContain("[1, 2, 3]");
    }

    @Test
    void 출력이_한도에서_잘리면_그_묶음만_한도를_2배로_올려_1회_다시_부른다() {
        givenContract(1, 30L);
        givenChunks(chunk(1L, 1, 1, 1));
        List<Integer> limits = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(gptClient.call(any(), any(), any(), any())).thenAnswer(inv -> {
            limits.add(((GptRequest) inv.getArgument(1)).maxOutputTokens());
            if (limits.size() == 1) {
                throw new GptCallException(GptFailureReason.LENGTH, "x", 1);
            }
            return ok(1, 1);
        });

        step.execute(CTX);

        assertThat(limits).containsExactly(null, 64000);
        verify(writer).completeChunk(1L, "{}", 1);
    }

    @Test
    void 두_번째도_잘리면_더_부르지_않고_실패한다() {
        givenContract(1, 30L);
        givenChunks(chunk(1L, 1, 1, 1));
        when(gptClient.call(any(), any(), any(), any())).thenThrow(new GptCallException(GptFailureReason.LENGTH, "x", 1));

        assertThatThrownBy(() -> step.execute(CTX)).isInstanceOf(GptCallException.class);

        verify(gptClient, times(2)).call(any(), any(), any(), any());
        verify(writer).failChunk(1L, 1);
    }

    @Test
    void 묶음_등록이_거절되면_이미_넣은_묶음을_취소하고_실패한다() {
        givenContract(7, 30L);
        givenChunks(chunk(1L, 1, 1, 5), chunk(2L, 2, 6, 7));
        List<Runnable> queued = new ArrayList<>();
        java.util.concurrent.Executor rejectSecond = r -> {
            if (!queued.isEmpty()) {
                throw new java.util.concurrent.RejectedExecutionException("가득 참");
            }
            queued.add(r);
        };
        ExtractStep rejecting = new ExtractStep(contractService, fileStorage, lawSearchService, gptClient, writer,
                new ObjectMapper(), rejectSecond, Clock.systemUTC(), GPT55);

        assertThatThrownBy(() -> rejecting.execute(CTX)).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        // 취소된 첫 묶음은 나중에 실행되어도 GPT 를 부르지 않는다
        queued.get(0).run();
        verify(gptClient, never()).call(any(), any(), any(), any());
        verify(writer, never()).saveMerged(anyLong(), any());
    }
}
