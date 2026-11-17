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
import lombok.RequiredArgsConstructor;
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

    /**
     * A1 분석 참조 확인. 내 분석이 아니거나 계약서가 삭제됐으면 ANALYSIS_NOT_FOUND 를 던져야 한다.
     * 소유권은 analysis → contract_version → contract.user_id 로 확인하는데 contract_version·contract 는 B 테이블이고,
     * versionId 로 계약서·소유자를 확인하는 B 메서드가 아직 없다. 소유권 검사 없이 값을 돌려주면 남의 분석이 열리므로
     * B 메서드가 생길 때까지 막아 둔다.
     */
    public AnalysisRefInfo getRef(UUID userId, Long analysisId) {
        throw new UnsupportedOperationException(
                "getRef: 버전의 계약서·소유자를 확인할 B 메서드(ContractService)가 아직 없음");
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
