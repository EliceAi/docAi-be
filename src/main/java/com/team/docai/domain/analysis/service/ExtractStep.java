package com.team.docai.domain.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptFailureReason;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.dto.ExtractResponse;
import com.team.docai.domain.analysis.entity.AnalysisChunk;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.ChunkStatus;
import com.team.docai.domain.contract.dto.ContractForAnalysisInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.law.dto.IssueTagInfo;
import com.team.docai.domain.law.service.LawSearchService;
import com.team.docai.global.config.AnalysisExtractConfig;
import com.team.docai.global.config.OpenAiProperties;
import com.team.docai.global.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * 분석 1차: 가린 사진 → 조항·주요 조건 (아키텍처 2~4단계). 진행 단계는 READING_TEXT 로 시작해 병합 저장 때 FINDING_TERMS.
 * <ol>
 *   <li>B 의 getForAnalysis 로 쪽 목록 (요청 때 버전과 다르면 실패), 문서 종류의 꼬리표 목록(DB issue_tag)</li>
 *   <li>묶음(analysis_chunk)마다 FileStorage.read 로 가린 사진을 읽어 GPT 1차 호출. 묶음은 1차 전용 풀에서 동시에
 *       (서버 전체 동시 수 = docai.analysis.extract.concurrency). 사진 바이트는 메모리에서만 쓰고 로그·DB 에 남기지 않는다.</li>
 *   <li>통신 오류 재시도는 GptClient 가 1회. 응답 내용 오류(깨진 JSON, 스키마 불일치)는 그 묶음만 1회,
 *       출력 잘림(LENGTH)은 출력 한도를 2배로 올려 그 묶음만 1회 다시 부른다
 *       (담당_연결지점 A8). 그래도 실패하면 묶음 FAILED 후 GptCallException → 파이프라인이 FAILED · TEMPORARY_ERROR.</li>
 *   <li>묶음마다 status·result_json·retry_count 저장 (이미 DONE 인 묶음은 저장된 결과를 다시 쓴다)</li>
 *   <li>병합(ExtractMerger) → TYPE_MISMATCH / PAGE_UNREADABLE 이면 AnalysisFailedException → 아니면 저장(ExtractWriter)</li>
 * </ol>
 * 트랜잭션 없이 돈다 (GPT 호출). 계약서 행 잠금을 잡지 않는다.
 */
@Slf4j
@Component
@Order(ExtractStep.ORDER)
@ConditionalOnProperty(prefix = "docai.analysis.extract", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExtractStep implements AnalysisStepHandler {

    /** 단계 순서: 1차(100) → 숫자 규칙(RuleStepHandler 500) → 근거 찾기(EvidenceStepHandler 600) → 2차 */
    public static final int ORDER = 100;
    static final String IMAGE_MIME = "image/jpeg";
    /** 그 묶음만 다시 부르는 응답 내용 오류 */
    static final Set<GptFailureReason> CONTENT_ERRORS = Set.of(GptFailureReason.INVALID_JSON,
            GptFailureReason.SCHEMA_MISMATCH);
    static final int CONTENT_RETRIES = 1;
    /** 출력이 한도에서 잘리면(LENGTH) 그 묶음만 한 번, 출력 한도를 이만큼 곱해 다시 부른다 */
    static final int LENGTH_RETRY_FACTOR = 2;

    private final ContractService contractService;
    private final FileStorage fileStorage;
    private final LawSearchService lawSearchService;
    private final GptClient gptClient;
    private final ExtractWriter extractWriter;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final Clock clock;
    private final OpenAiProperties openAiProperties;

    public ExtractStep(ContractService contractService, FileStorage fileStorage, LawSearchService lawSearchService,
                       GptClient gptClient, ExtractWriter extractWriter, ObjectMapper objectMapper,
                       @Qualifier(AnalysisExtractConfig.EXTRACT_EXECUTOR) Executor executor, Clock clock,
                       OpenAiProperties openAiProperties) {
        this.openAiProperties = openAiProperties;
        this.contractService = contractService;
        this.fileStorage = fileStorage;
        this.lawSearchService = lawSearchService;
        this.gptClient = gptClient;
        this.extractWriter = extractWriter;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public AnalysisStep progressStep() {
        return AnalysisStep.READING_TEXT;
    }

    @Override
    public void execute(AnalysisRunContext ctx) {
        ContractForAnalysisInfo contract = contractService.getForAnalysis(ctx.userId(), ctx.contractId());
        if (!contract.versionId().equals(ctx.versionId())) {
            throw new IllegalStateException("분석 중에 계약서 버전이 바뀌었습니다: analysisId=" + ctx.analysisId());
        }
        List<AnalysisChunk> chunks = extractWriter.findChunks(ctx.analysisId());
        if (chunks.isEmpty()) {
            throw new IllegalStateException("쪽 묶음이 없습니다: analysisId=" + ctx.analysisId());
        }
        List<IssueTagInfo> tags = lawSearchService.findIssueTags(ctx.documentTypeCode());
        String systemPrompt = ExtractPrompt.systemPrompt(ctx.documentTypeCode(), tags);
        Map<String, Object> schema = ExtractPrompt.schema(ctx.documentTypeCode(), tags);
        int totalPages = contract.pages().size();

        List<CompletableFuture<ExtractMerger.ChunkResult>> futures = new ArrayList<>();
        for (AnalysisChunk chunk : chunks) {
            try {
                futures.add(CompletableFuture.supplyAsync(
                        () -> readChunk(ctx, chunk, contract, totalPages, systemPrompt, schema), executor));
            } catch (RejectedExecutionException e) {
                // 1차 풀·대기열이 가득 참: 이미 넣은 묶음은 취소(아직 시작 안 한 것은 돌지 않음)하고 분석을 실패로 끝낸다
                futures.forEach(f -> f.cancel(true));
                log.warn("1차 묶음 등록이 거절되어 실패 처리함: analysisId={}, chunkNo={}", ctx.analysisId(),
                        chunk.getChunkNo());
                throw e;
            }
        }
        List<ExtractMerger.ChunkResult> results = awaitAll(futures);

        ExtractMerger.Result merged = ExtractMerger.merge(ctx.documentTypeCode(), results,
                tags.stream().map(IssueTagInfo::code).collect(Collectors.toSet()), LocalDate.now(clock));
        if (merged.failure() != null) {
            throw new AnalysisFailedException(merged.failure().reason(), merged.failure().pageNo(),
                    merged.failure().detectedTypeName(), "1차 읽기 결과로 분석을 끝냄");
        }
        if (!extractWriter.saveMerged(ctx.analysisId(), merged)) {
            log.info("1차 저장 전에 분석이 이미 끝나 저장하지 않음: analysisId={}", ctx.analysisId());
            return;
        }
        log.info("1차 완료: analysisId={}, chunks={}, clauses={}, keyTerms={}, partialPages={}, logs={}",
                ctx.analysisId(), results.size(), merged.clauses().size(), merged.keyTerms().size(),
                merged.partialPages(), merged.validationLog().size());
    }

    /** 묶음 하나: 저장된 결과가 있으면 그대로, 없으면 사진을 읽어 GPT 1차 (내용 오류면 1회 더) */
    ExtractMerger.ChunkResult readChunk(AnalysisRunContext ctx, AnalysisChunk chunk, ContractForAnalysisInfo contract,
                                       int totalPages, String systemPrompt, Map<String, Object> schema) {
        if (chunk.getStatus() == ChunkStatus.DONE && chunk.getResultJson() != null) {
            try {
                return new ExtractMerger.ChunkResult(chunk.getChunkNo(), chunk.getPageFrom(), chunk.getPageTo(),
                        objectMapper.readValue(chunk.getResultJson(), ExtractResponse.class));
            } catch (JsonProcessingException e) {
                log.warn("저장된 묶음 결과를 읽지 못해 다시 부름: chunkId={}", chunk.getChunkId());
            }
        }
        List<ExtractPrompt.PageImage> images = contract.pages().stream()
                .filter(p -> p.pageNo() >= chunk.getPageFrom() && p.pageNo() <= chunk.getPageTo())
                .map(p -> new ExtractPrompt.PageImage(p.pageNo(), fileStorage.read(p.imageKey()), IMAGE_MIME))
                .toList();
        if (images.isEmpty()) {
            throw new IllegalStateException("묶음에 쪽 사진이 없습니다: chunkId=" + chunk.getChunkId());
        }
        GptRequest request = GptRequest.of(systemPrompt,
                ExtractPrompt.userContent(chunk.getPageFrom(), chunk.getPageTo(), totalPages, images),
                ExtractPrompt.SCHEMA_NAME, schema);
        GptContext gptContext = GptContext.chunk(ctx.userId(), ctx.analysisId(), chunk.getChunkId());

        int retries = 0;
        int contentRetries = 0;
        boolean lengthRetried = false;
        while (true) {
            try {
                ExtractResponse response = gptClient.call(GptPurpose.EXTRACT, request, ExtractResponse.class, gptContext);
                extractWriter.completeChunk(chunk.getChunkId(), extractWriter.toJson(response), retries);
                return new ExtractMerger.ChunkResult(chunk.getChunkNo(), chunk.getPageFrom(), chunk.getPageTo(), response);
            } catch (GptCallException e) {
                Integer biggerLimit = biggerOutputLimit(request);
                if (e.getReason() == GptFailureReason.LENGTH && !lengthRetried && biggerLimit != null) {
                    lengthRetried = true;
                    retries++;
                    request = request.with(biggerLimit, request.reasoningEffort());
                    log.info("1차 출력이 한도에서 잘려 그 묶음만 한도 {} 로 다시 부름: analysisId={}, chunkNo={}",
                            biggerLimit, ctx.analysisId(), chunk.getChunkNo());
                    continue;
                }
                if (CONTENT_ERRORS.contains(e.getReason()) && contentRetries < CONTENT_RETRIES) {
                    contentRetries++;
                    retries++;
                    log.info("1차 응답 내용 오류로 그 묶음만 다시 부름: analysisId={}, chunkNo={}, reason={}",
                            ctx.analysisId(), chunk.getChunkNo(), e.getReason());
                    continue;
                }
                failChunkQuietly(chunk, retries);
                throw e;
            } catch (RuntimeException e) {
                failChunkQuietly(chunk, retries);
                throw e;
            }
        }
    }

    /** 지금 요청의 출력 한도(없으면 목적별 설정값) × 2. 둘 다 없으면(비추론 모델) null: 다시 부르지 않는다 */
    private Integer biggerOutputLimit(GptRequest request) {
        Integer current = request.maxOutputTokens() != null ? request.maxOutputTokens()
                : (openAiProperties.reasoningModel() ? openAiProperties.purpose(GptPurpose.EXTRACT.name()).maxCompletionTokens() : null);
        return current == null ? null : current * LENGTH_RETRY_FACTOR;
    }

    private void failChunkQuietly(AnalysisChunk chunk, int retries) {
        try {
            extractWriter.failChunk(chunk.getChunkId(), retries);
        } catch (RuntimeException e) {
            log.warn("묶음 실패 저장 실패: chunkId={}, error={}", chunk.getChunkId(), e.toString());
        }
    }

    /** 모든 묶음이 끝날 때까지 기다린다. 하나라도 실패하면 (다른 묶음이 끝난 뒤) 첫 실패를 그대로 던진다 */
    private static List<ExtractMerger.ChunkResult> awaitAll(List<CompletableFuture<ExtractMerger.ChunkResult>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("1차 묶음 실패", cause);
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }
}
