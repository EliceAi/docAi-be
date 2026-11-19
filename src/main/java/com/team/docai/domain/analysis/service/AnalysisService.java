package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRefInfo;
import com.team.docai.domain.analysis.dto.AnalysisSummaryInfo;
import com.team.docai.domain.analysis.dto.ComparisonSourceInfo;
import com.team.docai.domain.analysis.dto.ScheduleCandidateInfo;
import com.team.docai.domain.analysis.dto.VersionAnalysisStateInfo;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisChunk;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.ChunkStatus;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.FindingKind;
import com.team.docai.domain.analysis.repository.AnalysisChunkRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisFindingRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.repository.ScheduleCandidateRepository;
import com.team.docai.domain.contract.dto.ContractVersionInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * B 가 부르는 분석 연결 메서드 (담당_연결지점.md 5번 A1~A6). 호출한 쪽 트랜잭션 안에서 부른다.
 * A1 의 소유권 확인은 B {@code ContractService.getVersionOwner} 로 한다 (CLAUDE.md 연결 메서드 표).
 * 반환은 Info record 이고 엔티티는 내보내지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisService {

    private static final List<ClauseVerdict> NEEDS_CHECK_VERDICTS = List.of(ClauseVerdict.NEEDS_CHECK, ClauseVerdict.UNCERTAIN);

    private final AnalysisRepository analysisRepository;
    private final AnalysisChunkRepository analysisChunkRepository;
    private final AnalysisKeyTermRepository analysisKeyTermRepository;
    private final AnalysisClauseRepository analysisClauseRepository;
    private final AnalysisFindingRepository analysisFindingRepository;
    private final ScheduleCandidateRepository scheduleCandidateRepository;
    /** B 가 ContractService 에서 이 서비스(A2·A3)를 부르게 되면 생성자 순환이 생기므로 지연 조회한다 */
    private final ObjectProvider<ContractService> contractService;

    /**
     * A1 분석 참조 확인 (일정 후보 조회 등 경로에 analysisId 가 오는 B API 가 부른다). 호출한 쪽 트랜잭션 안에서 부른다.
     * ① 분석이 없으면 ANALYSIS_NOT_FOUND ② B {@code ContractService.getVersionOwner} 로 분석한 버전의 계약서가 내 것이고
     * 삭제되지 않았는지 확인한다. B 가 CONTRACT_NOT_FOUND 를 던지면(남의 것·삭제됨) ANALYSIS_NOT_FOUND 로 바꿔 던진다.
     * 완료 여부는 판단하지 않고 status 만 담는다. COMPLETED 가 아니면 부르는 쪽이 CONTRACT_NOT_ANALYZED 등으로 처리한다
     * (일정 후보 조회 명세 에러 표의 409 는 그 API 의 판단).
     */
    public AnalysisRefInfo getRef(UUID userId, Long analysisId) {
        if (userId == null || analysisId == null) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND);
        }
        Analysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
        ContractVersionInfo version = AnalysisOwnership.requireOwnedVersion(
                contractService.getObject(), userId, analysis.getVersionId());
        return new AnalysisRefInfo(analysis.getAnalysisId(), analysis.getVersionId(), version.contractId(),
                analysis.getStatus().name(), analysis.getCompletedAt());
    }

    /**
     * A2 버전 상태 판단 재료. hasAnalyzing 과 이 버전의 가장 최근 분석(없으면 null).
     */
    public VersionAnalysisStateInfo getVersionState(Long versionId) {
        boolean hasAnalyzing = analysisRepository.existsByVersionIdAndStatus(versionId, AnalysisStatus.ANALYZING);
        VersionAnalysisStateInfo.LatestAnalysis latest = analysisRepository
                .findFirstByVersionIdOrderByStartedAtDescAnalysisIdDesc(versionId)
                .map(VersionAnalysisStateInfo.LatestAnalysis::from)
                .orElse(null);
        return new VersionAnalysisStateInfo(hasAnalyzing, latest);
    }

    /**
     * A3 완료 분석 요약. title 은 항상 null (analysis 에 칸 없음, 계약서 이름은 B contract.title).
     *
     * @return COMPLETED 가 아니거나 없는 분석이면 empty
     */
    public Optional<AnalysisSummaryInfo> getSummary(Long analysisId) {
        return findCompleted(analysisId)
                .map(a -> new AnalysisSummaryInfo(
                        a.getAnalysisId(),
                        null,
                        a.getCompletedAt(),
                        analysisClauseRepository.countByAnalysisIdAndExcludedFalseAndVerdictIn(
                                a.getAnalysisId(), NEEDS_CHECK_VERDICTS),
                        analysisFindingRepository.countByAnalysisIdAndKind(a.getAnalysisId(), FindingKind.MISSING_ITEM),
                        scheduleCandidateRepository.countByAnalysisId(a.getAnalysisId())));
    }

    /**
     * A4 일정 후보 읽기 (후보 ID 순).
     *
     * @return COMPLETED 가 아니거나 없는 분석이면 빈 목록
     */
    public List<ScheduleCandidateInfo> getScheduleCandidates(Long analysisId) {
        if (findCompleted(analysisId).isEmpty()) {
            return List.of();
        }
        return scheduleCandidateRepository.findByAnalysisIdOrderByCandidateIdAsc(analysisId).stream()
                .map(ScheduleCandidateInfo::from)
                .toList();
    }

    /**
     * A5 비교용 분석 결과. 판정 중(JUDGING)·제외(excluded) 조항은 넣지 않는다.
     *
     * @return COMPLETED 가 아니거나 없는 분석이면 empty
     */
    public Optional<ComparisonSourceInfo> getComparisonSource(Long analysisId) {
        return findCompleted(analysisId).map(this::toComparisonSource);
    }

    /**
     * A6 수정본 확정 ⑥. 그 버전 분석들의 similar_contract_id 를 null 로. B 의 확정 트랜잭션 안에서만 부른다 (없으면 예외).
     * 영속성 컨텍스트를 비우지 않으므로 이 트랜잭션에서 이미 읽힌 Analysis 의 similarContractId 는 낡을 수 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearSimilarContract(Long versionId) {
        analysisRepository.clearSimilarContract(versionId);
    }

    private Optional<Analysis> findCompleted(Long analysisId) {
        return analysisRepository.findByAnalysisIdAndStatus(analysisId, AnalysisStatus.COMPLETED);
    }

    private ComparisonSourceInfo toComparisonSource(Analysis a) {
        List<AnalysisChunk> chunks = analysisChunkRepository.findByAnalysisIdOrderByChunkNoAsc(a.getAnalysisId());
        int pageCount = chunks.stream().mapToInt(AnalysisChunk::getPageTo).max().orElse(0);
        TreeSet<Integer> readPages = new TreeSet<>();
        chunks.stream()
                .filter(c -> c.getStatus() == ChunkStatus.DONE)
                .forEach(c -> IntStream.rangeClosed(c.getPageFrom(), c.getPageTo()).forEach(readPages::add));
        List<ComparisonSourceInfo.KeyTerm> keyTerms = analysisKeyTermRepository
                .findByAnalysisIdOrderBySortOrderAsc(a.getAnalysisId()).stream()
                .map(ComparisonSourceInfo.KeyTerm::from)
                .toList();
        List<ComparisonSourceInfo.Clause> clauses = analysisClauseRepository
                .findByAnalysisIdAndExcludedFalseAndVerdictNotOrderByPageNoAscClauseIdAsc(
                        a.getAnalysisId(), ClauseVerdict.JUDGING).stream()
                .map(ComparisonSourceInfo.Clause::from)
                .toList();
        return new ComparisonSourceInfo(
                a.getAnalysisId(),
                a.getDocumentTypeCode(),
                pageCount,
                List.copyOf(readPages),
                a.getPartialPages() == null ? List.of() : List.copyOf(a.getPartialPages()),
                keyTerms,
                clauses);
    }
}
