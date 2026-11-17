package com.team.docai.domain.analysis.service.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.AnalysisFinding;
import com.team.docai.domain.analysis.entity.NumberComparison;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.repository.AnalysisFindingRepository;
import com.team.docai.domain.analysis.service.rule.EvidenceCollector.RefDraft;
import com.team.docai.global.util.TextTruncator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 5·6단계 저장. 단계 계산은 트랜잭션 밖에서 끝내고 여기서는 짧은 트랜잭션으로 넣기만 한다.
 * 조항 칸 변경은 AnalysisClause 에 변경 메서드가 없어(공용 Entity 를 고치지 않음) 조건부 UPDATE 로 한다:
 * 판정 중(JUDGING)이고 제외되지 않은 그 분석의 조항일 때만 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class RuleWriter {

    static final String MIN_WAGE_TAG = "MIN_WAGE_SHORTFALL";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AnalysisClauseRefRepository refRepository;
    private final AnalysisFindingRepository findingRepository;

    /**
     * 최저임금 미달을 그 조항에 붙인다: 의심 표시(issue_found = true), basis_type = RULE, comparison_json, 결과 카드 제목,
     * 확인이 필요한 이유, 꼬리표가 비어 있으면 MIN_WAGE_SHORTFALL (issue_tag 에 있을 때만), RULE 근거(cited = true).
     *
     * @return 조항이 바뀌었는지. 판정 중이 아니거나 다른 분석의 조항이면 false 이고 근거도 넣지 않는다
     */
    @Transactional
    public boolean applyRuleResult(Long analysisId, Long clauseId, NumberComparison comparison, String headline,
                                   String whyCheck, RefDraft ruleRef) {
        int updated = jdbcTemplate.update("""
                        update analysis_clause
                        set issue_found = true, basis_type = 'RULE', comparison_json = cast(? as jsonb),
                            headline = ?, why_check = ?,
                            issue_tag_code = coalesce(issue_tag_code, (select t.code from issue_tag t where t.code = ?))
                        where clause_id = ? and analysis_id = ? and verdict = 'JUDGING' and excluded = false
                        """,
                toJson(comparison), TextTruncator.truncate(headline, AnalysisClause.HEADLINE_MAX_LENGTH), whyCheck,
                MIN_WAGE_TAG, clauseId, analysisId);
        if (updated != 1) {
            return false;
        }
        saveRefs(List.of(ruleRef));
        return true;
    }

    @Transactional
    public void saveRefs(List<RefDraft> drafts) {
        refRepository.saveAll(drafts.stream()
                .map(d -> AnalysisClauseRef.create(d.clauseId(), d.refLabel(), d.lawArticleId(), d.guidanceId(),
                        d.foundBy(), d.cited(), d.refTitle(), d.refText()))
                .toList());
    }

    @Transactional
    public void saveFindings(List<AnalysisFinding> findings) {
        findingRepository.saveAll(findings);
    }

    private String toJson(NumberComparison comparison) {
        try {
            return objectMapper.writeValueAsString(comparison);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("comparison_json 변환 실패", e);
        }
    }
}
