package com.team.docai.domain.law.client;

import java.time.LocalDate;
import java.util.List;

/**
 * 현행법령 본문 파싱 결과.
 *
 * @param deletedArticles   "제35조 삭제" 처럼 내용 없이 삭제된 조문 수 (저장하지 않음)
 * @param deletedParagraphs "③ 삭제" 처럼 삭제된 항 수 (저장하지 않음)
 * @param deletedItems      "3. 삭제" 처럼 삭제된 호·목 줄 수 (내용에 붙이지 않음)
 * @param headings          조문여부 = 전문 (장·절 제목) 수 (저장하지 않음, 설계_점검 32번)
 */
public record ParsedLaw(
        String sourceLawId,
        String name,
        String kind,
        String abbreviation,
        LocalDate promulgatedAt,
        LocalDate effectiveDate,
        List<ParsedArticle> articles,
        int deletedArticles,
        int deletedParagraphs,
        int deletedItems,
        int headings
) {
}
