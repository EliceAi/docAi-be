package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.LawHint;
import com.team.docai.domain.analysis.entity.RefFoundBy;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.service.AnalysisStepHandler;
import com.team.docai.domain.analysis.service.rule.EvidenceCollector.ExistingRef;
import com.team.docai.domain.analysis.service.rule.EvidenceCollector.RefDraft;
import com.team.docai.domain.law.dto.GuidanceInfo;
import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.service.LawSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 6단계 근거 찾기 (법령 DB, AI 없음) · 진행 단계 MATCHING_LAWS.
 * 의심 표시된(issue_found = true) 판정 중 조항마다 {@link EvidenceCollector} 순서로 근거를 모아
 * analysis_clause_ref 에 cited = false 로 저장한다 (조항당 최대 {@link #MAX_REFS_PER_CLAUSE}개, 5단계 RULE 근거 포함).
 * <ol>
 *   <li>꼬리표 → 조문 매핑 (issue_tag_article 우선순위)</li>
 *   <li>AI 법령 힌트(law_hints)를 LawSearchService 로 확인해 DB 에 있는 조문만</li>
 *   <li>칸이 남으면 전문 검색(search_keywords, 문서 종류별 관련 법령 안). 그래도 남으면 일상용어를 법령용어로 넓혀 채움
 *       (LawSearchService.searchWithDailyTerms)</li>
 *   <li>꼬리표의 해석 메모 1개</li>
 * </ol>
 * 조문은 기준일(law_base_date, 없으면 분석일)에 시행 중인 행. 현행본만 적재해 대신 쓴 행은 ref_text 앞에 표시한다.
 * 찾기는 트랜잭션 밖, 저장은 {@link RuleWriter#saveRefs} 한 번.
 */
@Slf4j
@Component
@Order(EvidenceStepHandler.ORDER)
@RequiredArgsConstructor
public class EvidenceStepHandler implements AnalysisStepHandler {

    /** 숫자 규칙({@link RuleStepHandler#ORDER}) 다음, 7단계 2차 앞. */
    public static final int ORDER = 600;
    static final int MAX_REFS_PER_CLAUSE = 5;

    private final AnalysisRepository analysisRepository;
    private final AnalysisClauseRepository clauseRepository;
    private final AnalysisClauseRefRepository refRepository;
    private final LawSearchService lawSearchService;
    private final RuleWriter ruleWriter;

    @Override
    public AnalysisStep progressStep() {
        return AnalysisStep.MATCHING_LAWS;
    }

    @Override
    public void execute(AnalysisRunContext ctx) {
        Analysis analysis = analysisRepository.findById(ctx.analysisId())
                .orElseThrow(() -> new IllegalStateException("분석이 없음: " + ctx.analysisId()));
        LocalDate baseDate = RuleValues.baseDate(analysis);
        List<AnalysisClause> suspicious = clauseRepository
                .findByAnalysisIdAndExcludedFalseAndVerdictNotOrderByPageNoAscClauseIdAsc(ctx.analysisId(),
                        ClauseVerdict.UNREADABLE).stream()
                .filter(c -> c.isIssueFound() && c.getVerdict() == ClauseVerdict.JUDGING)
                .toList();
        if (suspicious.isEmpty()) {
            log.info("근거 찾기: 의심 조항 없음, analysisId={}", ctx.analysisId());
            return;
        }
        List<Long> lawIds = lawSearchService.findLawIds(analysis.getDocumentTypeCode());
        List<RefDraft> drafts = new ArrayList<>();
        for (AnalysisClause clause : suspicious) {
            drafts.addAll(collect(clause, lawIds, baseDate));
        }
        if (!drafts.isEmpty()) {
            ruleWriter.saveRefs(drafts);
        }
        log.info("근거 찾기: 조항 {}개, 근거 {}개, analysisId={}", suspicious.size(), drafts.size(), ctx.analysisId());
    }

    List<RefDraft> collect(AnalysisClause clause, List<Long> lawIds, LocalDate baseDate) {
        List<ExistingRef> existing = refRepository.findByClauseIdOrderByRefIdAsc(clause.getClauseId()).stream()
                .map(ExistingRef::from).toList();
        String tag = clause.getIssueTagCode();
        List<GuidanceInfo> guidance = tag == null ? List.of() : lawSearchService.findGuidance(tag);
        EvidenceCollector collector = new EvidenceCollector(clause.getClauseId(), existing, guidance, baseDate,
                MAX_REFS_PER_CLAUSE);
        if (tag != null && collector.wantsMoreLaws()) {
            collector.addLaws(RefFoundBy.TAG_MAP, EvidenceCollector.orderTagArticles(
                    EvidenceCollector.notEndedBefore(lawSearchService.findByIssueTag(tag), baseDate)));
        }
        if (clause.getLawHints() != null) {
            for (LawHint hint : clause.getLawHints()) {
                if (!collector.wantsMoreLaws()) {
                    break;
                }
                if (hint != null) {
                    collector.addLaws(RefFoundBy.AI_HINT,
                            lawSearchService.findArticles(hint.law(), hint.article(), baseDate));
                }
            }
        }
        List<String> keywords = clause.getSearchKeywords();
        if (collector.wantsMoreLaws() && keywords != null && !keywords.isEmpty()) {
            List<LawArticleInfo> found = lawSearchService.searchWithDailyTerms(keywords, lawIds, baseDate,
                    MAX_REFS_PER_CLAUSE);
            collector.addLaws(RefFoundBy.SEARCH, found);
        }
        return collector.drafts();
    }
}
