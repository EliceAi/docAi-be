package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRequestInfo;
import com.team.docai.domain.analysis.dto.AnalysisRequestedEvent;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisChunk;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.ChunkStatus;
import com.team.docai.domain.analysis.repository.AnalysisChunkRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.contract.dto.ContractForAnalysisInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.config.AnalysisProperties;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 분석 요청의 트랜잭션 부분 (명세 "분석 요청" 비고의 처리 순서). AnalysisRequestService 가 부른다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisRequestWriter {

    private final ContractService contractService;
    private final AnalysisRepository analysisRepository;
    private final AnalysisChunkRepository analysisChunkRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AnalysisProperties properties;

    /**
     * B 의 lockForUpdate 는 MANDATORY 라 이 메서드의 트랜잭션 안에서만 부를 수 있다 (잠금은 커밋까지 유지).
     * 한 트랜잭션: ⓪ 소유권 먼저 확인 (없음·남의 것·삭제 → CONTRACT_NOT_FOUND). 남의 계약서 행에 잠금이 먼저 걸리지
     * 않게 하기 위해서다 ① 계약서 행 잠금 ② 잠근 뒤 다시 조회 (잠금을 기다리는 동안 바뀐 종류·버전·보관·삭제를 반영)
     * ③ 보관됨 → CONTRACT_ARCHIVED ④ 같은 버전에 ANALYZING 이 있으면 그것을 돌려줌
     * ⑤ 없으면 분석(ANALYZING · READING_TEXT, 요청 시점 문서 종류)과 쪽 묶음 저장 ⑥ 커밋 후 시작 이벤트.
     * latest_analysis_id 는 바꾸지 않는다 (COMPLETED 때만).
     * 분석 저장은 바로 flush 해서 uq_analysis_running 위반이 이 메서드 안에서 나게 한다 (AnalysisRequestService 가 처리).
     */
    @Transactional
    public AnalysisRequestInfo createOrGetRunning(UUID userId, Long contractId) {
        contractService.getForAnalysis(userId, contractId);
        contractService.lockForUpdate(contractId);
        ContractForAnalysisInfo contract = contractService.getForAnalysis(userId, contractId);
        if (contract.archived()) {
            throw new BusinessException(ErrorCode.CONTRACT_ARCHIVED);
        }

        Optional<Analysis> running = analysisRepository.findByVersionIdAndStatus(contract.versionId(), AnalysisStatus.ANALYZING);
        if (running.isPresent()) {
            Analysis a = running.get();
            return new AnalysisRequestInfo(a.getAnalysisId(), contractId, a.getStatus(), a.getProgressStep(),
                    (int) analysisChunkRepository.countByAnalysisIdAndStatus(a.getAnalysisId(), ChunkStatus.DONE),
                    (int) analysisChunkRepository.countByAnalysisId(a.getAnalysisId()));
        }

        Analysis analysis = analysisRepository.saveAndFlush(Analysis.create(contract.versionId(),
                contract.documentTypeCode(), properties.promptVersion(), OffsetDateTime.now()));
        Long analysisId = analysis.getAnalysisId();
        List<int[]> ranges = chunkRanges(contract.pages().stream().map(ContractForAnalysisInfo.PageInfo::pageNo).toList(),
                properties.pagesPerChunk());
        List<AnalysisChunk> chunks = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            chunks.add(AnalysisChunk.create(analysisId, i + 1, ranges.get(i)[0], ranges.get(i)[1]));
        }
        analysisChunkRepository.saveAll(chunks);

        eventPublisher.publishEvent(new AnalysisRequestedEvent(new AnalysisRunContext(
                analysisId, userId, contractId, contract.versionId(), contract.documentTypeCode())));
        return new AnalysisRequestInfo(analysisId, contractId, analysis.getStatus(), analysis.getProgressStep(),
                0, chunks.size());
    }

    /**
     * 쪽 묶음 범위 [시작 쪽, 끝 쪽]. 쪽 번호 순서대로 size 쪽씩 나누고 겹치지 않는다
     * (아키텍처 2단계 예: 12쪽 → 1~5, 6~10, 11~12). 쪽이 없으면 빈 목록.
     */
    static List<int[]> chunkRanges(List<Integer> pageNos, int size) {
        List<Integer> sorted = pageNos.stream().sorted().toList();
        List<int[]> ranges = new ArrayList<>();
        for (int from = 0; from < sorted.size(); from += size) {
            int to = Math.min(from + size, sorted.size()) - 1;
            ranges.add(new int[]{sorted.get(from), sorted.get(to)});
        }
        return ranges;
    }
}
