package com.team.docai.domain.analysis.service.query;

import com.team.docai.domain.analysis.dto.AnalysisChatInfo;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.repository.AnalysisResultReader;
import com.team.docai.domain.law.dto.ArticleNumber;
import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.service.LawSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 챗봇이 부르는 분석 읽기 (A 안의 기능 간 호출: chat 은 analysis Repository 를 직접 쓰지 않고 이 Service 만 부른다).
 * 소유권은 결과 조회와 같이 analysis → contract_version → contract.user_id 로 {@link ContractReadOnlyQuery} 가 확인한다
 * (임시 예외, 담당_연결지점 '임시 예외 (2026-11-18)').
 * <p>
 * 내 분석이 아니면 예외 대신 empty 를 돌려준다. 부르는 쪽이 같은 읽기 트랜잭션 안에서 다른 판단(대신 쓸 최신 분석 등)을
 * 이어 갈 수 있게 하기 위해서다 (트랜잭션 경계를 넘는 예외는 바깥 트랜잭션을 rollback-only 로 만든다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisChatQueryService {

    private final AnalysisRepository analysisRepository;
    private final AnalysisKeyTermRepository analysisKeyTermRepository;
    private final AnalysisClauseRepository analysisClauseRepository;
    private final AnalysisResultReader reader;
    private final ContractReadOnlyQuery contractQuery;
    private final LawSearchService lawSearchService;

    /** @return 없거나, 남의 것이거나, 삭제된 계약서의 분석이면 empty */
    public Optional<AnalysisChatInfo> findForChat(UUID userId, Long analysisId) {
        if (userId == null || analysisId == null) {
            return Optional.empty();
        }
        return analysisRepository.findById(analysisId)
                .flatMap(a -> contractQuery.findOwnedVersion(userId, a.getVersionId()).map(v -> toInfo(a, v.contractId())));
    }

    private AnalysisChatInfo toInfo(Analysis analysis, Long contractId) {
        if (analysis.getStatus() != AnalysisStatus.COMPLETED) {
            return new AnalysisChatInfo(analysis.getAnalysisId(), contractId, analysis.getStatus(),
                    analysis.getDocumentTypeCode(), analysis.getCompletedAt(), List.of(), List.of(), List.of(), List.of());
        }
        Long analysisId = analysis.getAnalysisId();
        List<AnalysisClause> clauses = analysisClauseRepository
                .findByAnalysisIdAndExcludedFalseAndVerdictNotOrderByPageNoAscClauseIdAsc(analysisId, ClauseVerdict.JUDGING);

        List<AnalysisChatInfo.KeyTerm> keyTerms = analysisKeyTermRepository.findByAnalysisIdOrderBySortOrderAsc(analysisId)
                .stream()
                .map(k -> new AnalysisChatInfo.KeyTerm(k.getLabel(), k.getValue(), k.getState().name()))
                .toList();
        List<AnalysisChatInfo.Clause> clauseInfos = clauses.stream()
                .map(c -> new AnalysisChatInfo.Clause(c.getClauseId(), c.getClauseKey(), c.getArticleNo(), c.getTitle(),
                        c.getVerdict().name(), c.getHeadline(), c.getOneLine(), c.getPageNo(), c.getEasyExplanation(),
                        c.getVerdict() == ClauseVerdict.NEEDS_CHECK ? c.getWhyCheck() : null,
                        c.getVerdict() == ClauseVerdict.UNREADABLE ? null : c.getOriginalText()))
                .toList();
        List<AnalysisChatInfo.Finding> findings = reader.findFindings(analysisId).stream()
                .map(f -> new AnalysisChatInfo.Finding(f.getKind().name(), f.getName(), f.getDescription(), f.getDueDate()))
                .toList();

        // 근거 법령: NEEDS_CHECK 조항이 인용한 조문만 (UNCERTAIN 은 근거 조문이 없다는 뜻이라 보내지 않음)
        List<Long> needsCheckIds = clauses.stream()
                .filter(c -> c.getVerdict() == ClauseVerdict.NEEDS_CHECK)
                .map(AnalysisClause::getClauseId)
                .toList();
        List<AnalysisClauseRef> refs = reader.findCitedLawRefs(needsCheckIds);
        Map<Long, LawArticleInfo> articles = refs.isEmpty() ? Map.of()
                : lawSearchService.getArticles(refs.stream().map(AnalysisClauseRef::getLawArticleId).toList()).stream()
                .collect(Collectors.toMap(LawArticleInfo::lawArticleId, Function.identity(), (x, y) -> x));
        List<AnalysisChatInfo.LawRef> lawRefs = refs.stream()
                .filter(r -> articles.containsKey(r.getLawArticleId()))
                .map(r -> {
                    LawArticleInfo a = articles.get(r.getLawArticleId());
                    return new AnalysisChatInfo.LawRef(r.getClauseId(), a.lawArticleId(),
                            a.lawName() + " " + ArticleNumber.label(a.articleNo(), a.articleBranchNo(), null),
                            a.title(), r.getRefText());
                })
                .toList();

        return new AnalysisChatInfo(analysisId, contractId, analysis.getStatus(), analysis.getDocumentTypeCode(),
                analysis.getCompletedAt(), keyTerms, clauseInfos, findings, lawRefs);
    }
}
