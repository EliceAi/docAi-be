package com.team.docai.web.analysis.dto.response;

import com.team.docai.domain.analysis.entity.BasisType;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.dto.ClauseDetailInfo;

import java.util.List;

/**
 * 조항 상세 조회 응답 (GET /api/analyses/{analysisId}/clauses/{clauseId}). 예시 분석의 clauseDetails 값도 이 모양.
 */
public record ClauseDetailResponse(
        Long clauseId,
        String articleNo,
        String title,
        ClauseVerdict verdict,
        String issueSummary,
        Original original,
        String easyExplanation,
        String whyCheck,
        List<Law> laws,
        BasisType basisType,
        List<RelatedClause> relatedClauses,
        List<String> askCounterpart,
        String oneLine
) {
    public record Original(String text, Integer pageNo, String location) {
    }

    public record Law(String lawName, String article, String articleTitle, String content) {
    }

    public record RelatedClause(Long clauseId, String articleNo, String title, String originalText) {
    }

    public static ClauseDetailResponse from(ClauseDetailInfo info) {
        ClauseDetailInfo.Original o = info.original();
        return new ClauseDetailResponse(
                info.clauseId(),
                info.articleNo(),
                info.title(),
                info.verdict(),
                info.issueSummary(),
                o == null ? null : new Original(o.text(), o.pageNo(), o.location()),
                info.easyExplanation(),
                info.whyCheck(),
                info.laws().stream().map(l -> new Law(l.lawName(), l.article(), l.articleTitle(), l.content())).toList(),
                info.basisType(),
                info.relatedClauses().stream()
                        .map(r -> new RelatedClause(r.clauseId(), r.articleNo(), r.title(), r.originalText()))
                        .toList(),
                info.askCounterpart(),
                info.oneLine());
    }
}
