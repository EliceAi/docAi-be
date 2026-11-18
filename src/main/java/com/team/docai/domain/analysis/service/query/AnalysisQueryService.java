package com.team.docai.domain.analysis.service.query;

import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisChunk;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.AnalysisClauseRelation;
import com.team.docai.domain.analysis.entity.AnalysisFinding;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.ChunkStatus;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.FindingKind;
import com.team.docai.domain.analysis.repository.AnalysisChunkRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRelationRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.repository.AnalysisResultReader;
import com.team.docai.domain.analysis.service.query.ContractReadOnlyQuery.OwnedVersion;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.ClauseSummary;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.Counts;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.Coverage;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.Failure;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.Finding;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.KeyTerm;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.LawRef;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.PriorityClause;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.RelatedClause;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.Result;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo.SimilarContract;
import com.team.docai.domain.analysis.dto.ClauseDetailInfo;
import com.team.docai.domain.analysis.dto.SampleAnalysisInfo;
import com.team.docai.domain.law.dto.ArticleNumber;
import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.service.LawSearchService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 분석 결과 조회, 조항 상세 조회, 예시 분석 조회 (8·10단계). 읽기만 한다.
 * <ul>
 *   <li>소유권: analysis → contract_version → contract.user_id. 남의 것·삭제된 계약서의 분석은 ANALYSIS_NOT_FOUND.
 *       B 메서드가 없어 지금은 {@link ContractReadOnlyQuery} 가 읽기 전용 SQL 로 확인한다 (B 메서드로 바꿀 곳).</li>
 *   <li>결과·조항은 COMPLETED 분석만. 판정 중(JUDGING)·제외(excluded) 조항은 어디에도 넣지 않는다.</li>
 *   <li>근거 법령은 AI 가 인용하고 검증을 통과한(cited) 조문만. 해석 메모는 보여 주지 않는다.</li>
 *   <li>UNCERTAIN 조항은 쉬운 설명과 물어볼 것만 보여 준다. 법적 판단 문구(why_check, issue_summary)와 법령은 넣지 않는다
 *       (분석 결과 조회 명세 비고).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisQueryService {

    /** 법률 자문 아님 안내와 상담처 (분석 결과 조회 명세 예시 문구) */
    public static final String DISCLAIMER = "법률 자문이 아니에요. 애매하면 고용노동부 1350 · 대한법률구조공단 132에 물어보세요.";

    private static final Set<ClauseVerdict> NEEDS_CHECK_VERDICTS = EnumSet.of(ClauseVerdict.NEEDS_CHECK, ClauseVerdict.UNCERTAIN);

    private final AnalysisRepository analysisRepository;
    private final AnalysisChunkRepository analysisChunkRepository;
    private final AnalysisKeyTermRepository analysisKeyTermRepository;
    private final AnalysisClauseRepository analysisClauseRepository;
    private final AnalysisClauseRefRepository analysisClauseRefRepository;
    private final AnalysisClauseRelationRepository analysisClauseRelationRepository;
    private final AnalysisResultReader reader;
    private final ContractReadOnlyQuery contractQuery;
    private final LawSearchService lawSearchService;
    private final SampleAnalysisReader sampleReader;
    private final Clock clock;

    /**
     * 분석 결과 조회. ① 분석이 없으면 404 ② 내 계약서(삭제 안 됨)의 분석이 아니면 404
     * ③ 진행 단계·읽기 진행 ④ FAILED 면 실패 정보 ⑤ COMPLETED 면 결과.
     */
    public AnalysisResultInfo getResult(UUID userId, Long analysisId) {
        Analysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
        OwnedVersion version = findOwnedVersion(userId, analysis);

        List<AnalysisChunk> chunks = analysisChunkRepository.findByAnalysisIdOrderByChunkNoAsc(analysisId);
        int readDone = (int) chunks.stream().filter(c -> c.getStatus() == ChunkStatus.DONE).count();

        Failure failure = analysis.getStatus() == AnalysisStatus.FAILED
                ? new Failure(analysis.getFailReason(), analysis.getFailPageNo(), analysis.getDetectedTypeName())
                : null;
        Result result = analysis.getStatus() == AnalysisStatus.COMPLETED
                ? buildResult(userId, analysis, version, chunks)
                : null;
        return new AnalysisResultInfo(analysisId, version.contractId(), version.versionNo(), analysis.getStatus(),
                analysis.getProgressStep(), readDone, chunks.size(), failure, result);
    }

    /**
     * 조항 상세 조회. ① 분석이 없거나 내 것이 아니면 ANALYSIS_NOT_FOUND ② COMPLETED 가 아니면 ANALYSIS_NOT_COMPLETED
     * ③ 이 분석의 조항이 아니거나 제외·판정 중이면 CLAUSE_NOT_FOUND.
     */
    public ClauseDetailInfo getClause(UUID userId, Long analysisId, Long clauseId) {
        Analysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
        findOwnedVersion(userId, analysis);
        if (analysis.getStatus() != AnalysisStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED);
        }
        AnalysisClause clause = reader.findVisibleClause(analysisId, clauseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLAUSE_NOT_FOUND));

        List<ClauseDetailInfo.LawDetail> laws = List.of();
        if (!isUncertain(clause)) {
            List<AnalysisClauseRef> refs = analysisClauseRefRepository.findByClauseIdOrderByRefIdAsc(clauseId).stream()
                    .filter(r -> r.isCited() && r.getLawArticleId() != null)
                    .toList();
            Map<Long, LawArticleInfo> articles = loadArticles(refs.stream().map(AnalysisClauseRef::getLawArticleId).toList());
            laws = refs.stream()
                    .filter(r -> articles.containsKey(r.getLawArticleId()))
                    .map(r -> {
                        LawArticleInfo a = articles.get(r.getLawArticleId());
                        return new ClauseDetailInfo.LawDetail(a.lawName(),
                                ArticleNumber.label(a.articleNo(), a.articleBranchNo(), a.paragraphNo()),
                                a.title(), r.getRefText());
                    })
                    .toList();
        }

        List<Long> relatedIds = analysisClauseRelationRepository.findByClauseId(clauseId).stream()
                .map(AnalysisClauseRelation::getRelatedClauseId)
                .toList();
        List<ClauseDetailInfo.RelatedClause> related = reader.findVisibleClauses(analysisId, relatedIds).stream()
                .map(c -> new ClauseDetailInfo.RelatedClause(c.getClauseId(), c.getArticleNo(), c.getTitle(),
                        originalText(c)))
                .toList();

        boolean hideJudgment = clause.getVerdict() == ClauseVerdict.OK || isUncertain(clause);
        return new ClauseDetailInfo(
                clause.getClauseId(),
                clause.getArticleNo(),
                clause.getTitle(),
                clause.getVerdict(),
                hideJudgment ? null : clause.getIssueSummary(),
                new ClauseDetailInfo.Original(originalText(clause), clause.getPageNo(), clause.getLocation()),
                clause.getEasyExplanation(),
                hideJudgment ? null : clause.getWhyCheck(),
                laws,
                clause.getBasisType(),
                related,
                copyOrEmpty(clause.getAskCounterpartJson()),
                clause.getOneLine());
    }

    /** 예시 분석 조회. 예시가 없는 문서 종류면 SAMPLE_NOT_FOUND */
    public SampleAnalysisInfo getSample(String documentTypeCode) {
        return sampleReader.find(documentTypeCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAMPLE_NOT_FOUND));
    }

    private OwnedVersion findOwnedVersion(UUID userId, Analysis analysis) {
        return contractQuery.findOwnedVersion(userId, analysis.getVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
    }

    private Result buildResult(UUID userId, Analysis analysis, OwnedVersion version, List<AnalysisChunk> chunks) {
        Long analysisId = analysis.getAnalysisId();
        List<AnalysisClause> clauses = analysisClauseRepository
                .findByAnalysisIdAndExcludedFalseAndVerdictNotOrderByPageNoAscClauseIdAsc(analysisId, ClauseVerdict.JUDGING);
        Map<Long, AnalysisClause> visible = clauses.stream()
                .collect(Collectors.toMap(AnalysisClause::getClauseId, Function.identity(), (x, y) -> x, LinkedHashMap::new));
        List<Long> clauseIds = List.copyOf(visible.keySet());

        Map<Long, List<AnalysisClauseRef>> refsByClause = reader.findCitedLawRefs(clauseIds).stream()
                .collect(Collectors.groupingBy(AnalysisClauseRef::getClauseId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<Long>> relatedByClause = reader.findRelations(clauseIds).stream()
                .collect(Collectors.groupingBy(AnalysisClauseRelation::getClauseId,
                        Collectors.mapping(AnalysisClauseRelation::getRelatedClauseId, Collectors.toList())));
        List<AnalysisFinding> findings = reader.findFindings(analysisId);

        List<Long> articleIds = new ArrayList<>();
        refsByClause.values().forEach(refs -> refs.forEach(r -> articleIds.add(r.getLawArticleId())));
        findings.stream().map(AnalysisFinding::getLawArticleId).filter(Objects::nonNull).forEach(articleIds::add);
        Map<Long, LawArticleInfo> articles = loadArticles(articleIds);

        List<PriorityClause> priority = clauses.stream()
                .filter(c -> c.getPriority() != null && NEEDS_CHECK_VERDICTS.contains(c.getVerdict()))
                .sorted(Comparator.comparing(AnalysisClause::getPriority).thenComparing(AnalysisClause::getClauseId))
                .map(c -> new PriorityClause(
                        c.getClauseId(),
                        c.getVerdict(),
                        c.getHeadline(),
                        isUncertain(c) ? null : c.getWhyCheck(),
                        c.getComparisonJson(),
                        isUncertain(c)
                                ? List.of()
                                : lawRefs(refsByClause.getOrDefault(c.getClauseId(), List.of()).stream()
                                        .map(AnalysisClauseRef::getLawArticleId).toList(), articles),
                        c.getBasisType(),
                        relatedByClause.getOrDefault(c.getClauseId(), List.of()).stream()
                                .map(visible::get)
                                .filter(Objects::nonNull)
                                .sorted(Comparator.comparingInt(AnalysisClause::getPageNo)
                                        .thenComparing(AnalysisClause::getClauseId))
                                .map(r -> new RelatedClause(r.getClauseId(), r.getArticleNo(), r.getTitle()))
                                .toList()))
                .toList();

        List<Finding> missingItems = findings.stream()
                .filter(f -> f.getKind() == FindingKind.MISSING_ITEM)
                .map(f -> new Finding(f.getName(), f.getDescription(), null, findingLaws(f, articles)))
                .toList();
        List<Finding> deadlines = findings.stream()
                .filter(f -> f.getKind() == FindingKind.DEADLINE)
                .map(f -> new Finding(f.getName(), f.getDescription(), f.getDueDate(), findingLaws(f, articles)))
                .toList();

        long needsCheck = clauses.stream().filter(c -> NEEDS_CHECK_VERDICTS.contains(c.getVerdict())).count();

        TreeSet<Integer> readPages = new TreeSet<>();
        chunks.stream()
                .filter(c -> c.getStatus() == ChunkStatus.DONE)
                .forEach(c -> IntStream.rangeClosed(c.getPageFrom(), c.getPageTo()).forEach(readPages::add));
        Coverage coverage = new Coverage(version.pageCount(), List.copyOf(readPages),
                copyOrEmpty(analysis.getPartialPages()), version.maskedCount());

        List<KeyTerm> keyTerms = analysisKeyTermRepository.findByAnalysisIdOrderBySortOrderAsc(analysisId).stream()
                .map(k -> new KeyTerm(k.getLabel(), k.getValue(), k.getState(), k.getSource()))
                .toList();

        List<ClauseSummary> clauseSummaries = clauses.stream()
                .map(c -> new ClauseSummary(c.getClauseId(), c.getArticleNo(), c.getTitle(), c.getVerdict(), c.getPageNo()))
                .toList();

        return new Result(
                version.title(),
                analysis.getDocumentTypeCode(),
                contractQuery.findCheckLevel(analysis.getDocumentTypeCode()),
                inZone(analysis.getCompletedAt()),
                coverage,
                keyTerms,
                new Counts(needsCheck, missingItems.size(), deadlines.size(), clauses.size()),
                priority,
                missingItems,
                deadlines,
                clauseSummaries,
                copyOrEmpty(analysis.getQuestionsJson()),
                copyOrEmpty(analysis.getLimitationsJson()),
                reader.countFoundDates(analysisId),
                similarContract(userId, analysis, version),
                DISCLAIMER);
    }

    /** 자기 자신을 가리키면 보여 주지 않는다 (설계_점검 21번 이중 방어). 보관·삭제·소유 조건은 쿼리가 거른다 */
    private SimilarContract similarContract(UUID userId, Analysis analysis, OwnedVersion version) {
        Long similarId = analysis.getSimilarContractId();
        if (similarId == null || similarId.equals(version.contractId())) {
            return null;
        }
        return contractQuery.findSimilarContract(userId, similarId)
                .map(s -> new SimilarContract(s.contractId(), s.title(), inZone(s.analyzedAt()), s.scheduleCount()))
                .orElse(null);
    }

    private Map<Long, LawArticleInfo> loadArticles(List<Long> lawArticleIds) {
        if (lawArticleIds.isEmpty()) {
            return Map.of();
        }
        return lawSearchService.getArticles(lawArticleIds).stream()
                .collect(Collectors.toMap(LawArticleInfo::lawArticleId, Function.identity(), (x, y) -> x));
    }

    /** 조문 단위로 묶어 중복 없이 (같은 조의 여러 항을 인용해도 "제6조" 한 번) */
    private static List<LawRef> lawRefs(List<Long> lawArticleIds, Map<Long, LawArticleInfo> articles) {
        Set<LawRef> refs = new LinkedHashSet<>();
        for (Long id : lawArticleIds) {
            LawArticleInfo a = articles.get(id);
            if (a != null) {
                refs.add(new LawRef(a.lawName(), ArticleNumber.label(a.articleNo(), a.articleBranchNo(), null)));
            }
        }
        return List.copyOf(refs);
    }

    private static List<LawRef> findingLaws(AnalysisFinding finding, Map<Long, LawArticleInfo> articles) {
        return finding.getLawArticleId() == null ? List.of() : lawRefs(List.of(finding.getLawArticleId()), articles);
    }

    /** 근거 조문을 찾지 못한 조항: 법적 판단 문구와 법령을 보여 주지 않는다 */
    private static boolean isUncertain(AnalysisClause clause) {
        return clause.getVerdict() == ClauseVerdict.UNCERTAIN;
    }

    private static String originalText(AnalysisClause clause) {
        return clause.getVerdict() == ClauseVerdict.UNREADABLE ? null : clause.getOriginalText();
    }

    private static <T> List<T> copyOrEmpty(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    private OffsetDateTime inZone(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(clock.getZone()).toOffsetDateTime();
    }
}
