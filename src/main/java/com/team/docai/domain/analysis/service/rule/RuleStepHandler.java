package com.team.docai.domain.analysis.service.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisFinding;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.FindingKind;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.entity.RefFoundBy;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.repository.AnalysisFindingRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.service.AnalysisStepHandler;
import com.team.docai.domain.analysis.service.rule.EvidenceCollector.ExistingRef;
import com.team.docai.domain.analysis.service.rule.EvidenceCollector.RefDraft;
import com.team.docai.domain.analysis.service.rule.MinimumWageRule.Outcome;
import com.team.docai.domain.analysis.service.rule.MinimumWageRule.Result;
import com.team.docai.domain.analysis.service.rule.RequiredItems.Item;
import com.team.docai.domain.analysis.service.rule.RequiredItems.KeyTermFact;
import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.service.LawSearchService;
import com.team.docai.global.util.TextTruncator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 5단계 숫자 규칙 (코드, AI 없음). 1차가 저장한 주요 조건(value_json)과 rule_param 으로 계산한다.
 * <ul>
 *   <li>최저임금(MIN_WAGE, 근로계약서): 기준일(law_base_date, 없으면 분석일)에 적용되는 기준값과 비교.
 *       미달이면 임금이 나온 조항(key_term.clause_id)에 의심 표시·RULE 근거(cited = true)·숫자 비교를 붙인다</li>
 *   <li>빠진 필수 항목: {@link RequiredItems} 목록 대비 MISSING → analysis_finding MISSING_ITEM (근거 조문 연결)</li>
 *   <li>기한(DEADLINE): 지금 DB 에 근거 법령이 있는 기한 규칙이 없어 만들지 않는다 (임대차 신고는 부동산 거래신고법 미적재)</li>
 * </ul>
 * 계산은 트랜잭션 밖, 저장은 {@link RuleWriter} 의 짧은 트랜잭션. 저장할 것이 없으면 아무것도 쓰지 않는다.
 */
@Slf4j
@Component
@Order(RuleStepHandler.ORDER)
@RequiredArgsConstructor
public class RuleStepHandler implements AnalysisStepHandler {

    /** 5단계(1차 추출·병합) 다음, 근거 찾기({@link EvidenceStepHandler#ORDER}) 앞. */
    public static final int ORDER = 500;
    static final String MIN_WAGE = "MIN_WAGE";
    static final Set<String> WAGE_LABELS = Set.of("임금", "급여");
    static final Set<String> HOURS_LABELS = Set.of("근로시간", "소정근로시간");

    private final AnalysisRepository analysisRepository;
    private final AnalysisKeyTermRepository keyTermRepository;
    private final AnalysisClauseRefRepository refRepository;
    private final AnalysisFindingRepository findingRepository;
    private final RuleParamReader ruleParamReader;
    private final LawSearchService lawSearchService;
    private final RuleWriter ruleWriter;
    private final ObjectMapper objectMapper;

    @Override
    public AnalysisStep progressStep() {
        return AnalysisStep.FINDING_TERMS;
    }

    @Override
    public void execute(AnalysisRunContext ctx) {
        Analysis analysis = analysisRepository.findById(ctx.analysisId())
                .orElseThrow(() -> new IllegalStateException("분석이 없음: " + ctx.analysisId()));
        LocalDate baseDate = RuleValues.baseDate(analysis);
        String documentType = analysis.getDocumentTypeCode();
        List<AnalysisKeyTerm> keyTerms = keyTermRepository.findByAnalysisIdOrderBySortOrderAsc(ctx.analysisId());
        if ("EMPLOYMENT".equals(documentType)) {
            checkMinimumWage(ctx.analysisId(), keyTerms, baseDate);
        }
        saveMissingItems(ctx.analysisId(), documentType, keyTerms, baseDate,
                wageOnlyDocument(analysis.getExtractionJson()));
    }

    void checkMinimumWage(Long analysisId, List<AnalysisKeyTerm> keyTerms, LocalDate baseDate) {
        Optional<AnalysisKeyTerm> wage = findWithValue(keyTerms, WAGE_LABELS);
        if (wage.isEmpty()) {
            log.info("최저임금 규칙 건너뜀 (임금 값 없음): analysisId={}", analysisId);
            return;
        }
        Optional<RuleParamReader.RuleStandard> param = ruleParamReader.find(MIN_WAGE, baseDate);
        Optional<BigDecimal> hourly = param.flatMap(p -> RuleValues.positive(p.value(), "hourly"));
        if (param.isEmpty() || hourly.isEmpty()) {
            log.warn("최저임금 규칙 건너뜀 (기준일 {} 에 적용할 rule_param 없음): analysisId={}", baseDate, analysisId);
            return;
        }
        MinimumWageRule.Standard standard = new MinimumWageRule.Standard(hourly.get(), param.get().validFrom(),
                param.get().lawArticleId());
        Result result = MinimumWageRule.check(wage.get().getValueJson(),
                hoursJson(keyTerms), standard);
        if (result.outcome() != Outcome.SHORTFALL) {
            log.info("최저임금 규칙 결과 {}{}: analysisId={}", result.outcome(),
                    result.skipReason() == null ? "" : " (" + result.skipReason() + ")", analysisId);
            return;
        }
        Long clauseId = wage.get().getClauseId();
        if (clauseId == null) {
            log.warn("최저임금 미달이지만 임금이 나온 조항이 없어 붙이지 못함: analysisId={}", analysisId);
            return;
        }
        List<LawArticleInfo> article = lawSearchService.getArticles(List.of(standard.lawArticleId()));
        if (article.isEmpty()) {
            log.error("최저임금 근거 조문이 없음: lawArticleId={}", standard.lawArticleId());
            return;
        }
        List<ExistingRef> existing = refRepository.findByClauseIdOrderByRefIdAsc(clauseId).stream()
                .map(ExistingRef::from).toList();
        RefDraft ref = EvidenceCollector.lawRef(clauseId, "L" + EvidenceCollector.nextNumber(existing),
                RefFoundBy.RULE, true, article.get(0), baseDate);
        boolean applied = ruleWriter.applyRuleResult(analysisId, clauseId, result.comparison(), result.headline(),
                result.whyCheck(), ref);
        log.info("최저임금 미달 {}: analysisId={}, clauseId={}", applied ? "붙임" : "못 붙임(판정 중 조항 아님)",
                analysisId, clauseId);
    }

    void saveMissingItems(Long analysisId, String documentType, List<AnalysisKeyTerm> keyTerms, LocalDate baseDate,
                          boolean wageOnlyDocument) {
        if (findingRepository.countByAnalysisIdAndKind(analysisId, FindingKind.MISSING_ITEM) > 0) {
            log.info("빠진 항목이 이미 저장돼 있어 건너뜀: analysisId={}", analysisId);
            return;
        }
        List<KeyTermFact> facts = keyTerms.stream().map(k -> new KeyTermFact(k.getLabel(), k.getState())).toList();
        List<String> unmatched = RequiredItems.unmatched(documentType, facts);
        if (!unmatched.isEmpty()) {
            log.warn("필수 항목에 맞는 주요 조건 라벨이 없어 누락 판단을 못 함: analysisId={}, items={}", analysisId, unmatched);
        }
        List<Item> missing = RequiredItems.findMissing(documentType, facts, wageOnlyDocument);
        List<AnalysisFinding> findings = new ArrayList<>();
        int order = 1;
        for (Item item : missing) {
            Long lawArticleId = lawSearchService.findArticles(item.lawName(), item.article(), baseDate).stream()
                    .findFirst().map(LawArticleInfo::lawArticleId).orElse(null);
            findings.add(AnalysisFinding.create(analysisId, FindingKind.MISSING_ITEM,
                    TextTruncator.truncate(item.name(), AnalysisFinding.NAME_MAX_LENGTH),
                    TextTruncator.truncate(item.description(), AnalysisFinding.DESCRIPTION_MAX_LENGTH),
                    null, lawArticleId, order++));
        }
        if (!findings.isEmpty()) {
            ruleWriter.saveFindings(findings);
        }
        log.info("빠진 필수 항목 {}개: analysisId={}", findings.size(), analysisId);
    }

    /**
     * extraction_json 최상위 "wageOnlyDocument" (임금만 정한 별도 문서). 없거나 읽을 수 없으면 false.
     * 위치는 5단계 출력에 맞춰 바뀔 수 있다.
     */
    boolean wageOnlyDocument(String extractionJson) {
        if (extractionJson == null || extractionJson.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(extractionJson).path("wageOnlyDocument").asBoolean(false);
        } catch (JsonProcessingException e) {
            log.warn("extraction_json 을 읽지 못해 wageOnlyDocument = false 로 봄");
            return false;
        }
    }

    /**
     * 근로시간 value_json. 근로시간 행이 아예 없으면 null (최저임금 월 환산은 209시간),
     * 행은 있는데 쓸 값이 없으면(MISSING·UNREADABLE·MASKED 등) 빈 Map (월급·연봉은 계산하지 않음, 단시간일 수 있어서).
     */
    static Map<String, Object> hoursJson(List<AnalysisKeyTerm> keyTerms) {
        Optional<AnalysisKeyTerm> valued = findWithValue(keyTerms, HOURS_LABELS);
        if (valued.isPresent()) {
            return valued.get().getValueJson();
        }
        Set<String> norm = HOURS_LABELS.stream().map(RuleValues::normalizeLabel).collect(Collectors.toSet());
        boolean present = keyTerms.stream().anyMatch(k -> norm.contains(RuleValues.normalizeLabel(k.getLabel())));
        return present ? Map.of() : null;
    }

    /** 라벨이 맞고 값이 있는(OK, NEEDS_CHECK, value_json 있음) 첫 주요 조건. */
    private static Optional<AnalysisKeyTerm> findWithValue(List<AnalysisKeyTerm> keyTerms, Set<String> labels) {
        Set<String> norm = labels.stream().map(RuleValues::normalizeLabel).collect(Collectors.toSet());
        return keyTerms.stream()
                .filter(k -> norm.contains(RuleValues.normalizeLabel(k.getLabel())))
                .filter(k -> (k.getState() == KeyTermState.OK || k.getState() == KeyTermState.NEEDS_CHECK)
                        && k.getValueJson() != null)
                .findFirst();
    }
}
