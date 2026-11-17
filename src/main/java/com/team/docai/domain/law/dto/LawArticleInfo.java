package com.team.docai.domain.law.dto;

import com.team.docai.domain.law.entity.LawArticle;

import java.time.LocalDate;

/**
 * 조문 한 행 (항 단위). 근거 찾기(6단계)가 analysis_clause_ref 의 ref_title·ref_text 스냅샷을 만들 때 쓴다.
 *
 * @param score             전문 검색 점수 (ts_rank). 검색이 아닌 조회면 null
 * @param fallbackToCurrent 기준일(계약일)에 시행 중인 행이 DB 에 없어(기준일이 가장 옛 개정본 시행일보다 앞섬)
 *                          가장 가까운 개정본 행으로 대신했는지. 현행본만 적재하므로 그 행은 현행 행이다.
 *                          6단계는 이 값이 true 면 "계약일 당시 조문이 아닐 수 있음"을 알 수 있다
 */
public record LawArticleInfo(
        Long lawArticleId,
        Long lawId,
        String lawName,
        int articleNo,
        int articleBranchNo,
        Integer paragraphNo,
        String title,
        String content,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Double score,
        boolean fallbackToCurrent
) {
    /** @param baseDate 기준일. null 이면 대체 판단을 하지 않는다 (ID 로 직접 읽은 경우) */
    public static LawArticleInfo of(LawArticle a, String lawName, Double score, LocalDate baseDate) {
        boolean fallback = baseDate != null && a.getEffectiveFrom().isAfter(baseDate);
        return new LawArticleInfo(a.getLawArticleId(), a.getLawId(), lawName, a.getArticleNo(), a.getArticleBranchNo(),
                a.getParagraphNo(), a.getTitle(), a.getContent(), a.getEffectiveFrom(), a.getEffectiveTo(), score, fallback);
    }

    /** "근로기준법 제20조", 항이 있으면 "근로기준법 제17조제1항". */
    public String label() {
        return lawName + " " + ArticleNumber.label(articleNo, articleBranchNo, paragraphNo);
    }
}
