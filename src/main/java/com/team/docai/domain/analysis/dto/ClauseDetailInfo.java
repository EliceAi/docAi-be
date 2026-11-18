package com.team.docai.domain.analysis.dto;

import com.team.docai.domain.analysis.entity.BasisType;
import com.team.docai.domain.analysis.entity.ClauseVerdict;

import java.util.List;

/**
 * 조항 상세 조회 (GET /api/analyses/{analysisId}/clauses/{clauseId}) 의 결과. 예시 분석의 clauseDetails 도 이 모양이다.
 *
 * @param issueSummary OK·UNCERTAIN 이면 null (UNCERTAIN 은 법적 판단 문구를 보여 주지 않는다)
 * @param whyCheck     OK·UNCERTAIN 이면 null
 * @param laws         AI 가 인용하고 검증을 통과한 조문(cited)만. UNCERTAIN 이면 빈 배열. 해석 메모는 넣지 않는다
 */
public record ClauseDetailInfo(
        Long clauseId,
        String articleNo,
        String title,
        ClauseVerdict verdict,
        String issueSummary,
        Original original,
        String easyExplanation,
        String whyCheck,
        List<LawDetail> laws,
        BasisType basisType,
        List<RelatedClause> relatedClauses,
        List<String> askCounterpart,
        String oneLine
) {

    /** text 는 UNREADABLE 이면 null */
    public record Original(String text, Integer pageNo, String location) {
    }

    /**
     * @param article      "제20조", 항 단위 조문이면 "제17조제1항"
     * @param articleTitle 조문 제목 (law_article.title)
     * @param content      분석 당시 AI 에게 보낸 내용 그대로 (analysis_clause_ref.ref_text)
     */
    public record LawDetail(String lawName, String article, String articleTitle, String content) {
    }

    /** title·originalText 는 null 일 수 있다 (제목 없음, UNREADABLE) */
    public record RelatedClause(Long clauseId, String articleNo, String title, String originalText) {
    }
}
