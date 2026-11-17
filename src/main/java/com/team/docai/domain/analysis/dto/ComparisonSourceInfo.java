package com.team.docai.domain.analysis.dto;

import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.ClauseVerdict;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A5 AnalysisService.getComparisonSource 의 반환 (COMPLETED 분석만). 판정 중(JUDGING)·제외(excluded) 조항은 넣지 않는다.
 *
 * @param pageCount    분석한 쪽 수 (쪽 묶음의 마지막 쪽)
 * @param readPages    읽은 쪽 (DONE 묶음이 덮는 쪽, 오름차순)
 * @param partialPages 일부만 읽은 쪽 (analysis.partial_pages)
 */
public record ComparisonSourceInfo(
        Long analysisId,
        String documentTypeCode,
        int pageCount,
        List<Integer> readPages,
        List<Integer> partialPages,
        List<KeyTerm> keyTerms,
        List<Clause> clauses
) {

    /**
     * state: OK / NEEDS_CHECK / MISSING / UNREADABLE / MASKED.
     * valueJson 은 엔티티 값과 분리한 읽기 전용 복사본 (값에 null 이 있을 수 있어 Map.copyOf 를 쓰지 않음). 없으면 null
     */
    public record KeyTerm(String label, String value, Map<String, Object> valueJson, String state) {

        public static KeyTerm from(AnalysisKeyTerm t) {
            Map<String, Object> json = t.getValueJson() == null
                    ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(t.getValueJson()));
            return new KeyTerm(t.getLabel(), t.getValue(), json, t.getState().name());
        }
    }

    /** originalText 는 UNREADABLE 이면 null. verdict: OK / NEEDS_CHECK / UNCERTAIN / UNREADABLE */
    public record Clause(Long clauseId, String articleNo, String title, String originalText, int pageNo, String verdict) {

        public static Clause from(AnalysisClause c) {
            String originalText = c.getVerdict() == ClauseVerdict.UNREADABLE ? null : c.getOriginalText();
            return new Clause(c.getClauseId(), c.getArticleNo(), c.getTitle(), originalText, c.getPageNo(),
                    c.getVerdict().name());
        }
    }
}
