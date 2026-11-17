package com.team.docai.domain.law.client;

import java.time.LocalDate;

/**
 * law_article 한 행이 될 값. 항 단위 (항이 없으면 paragraphNo = null 인 조문 전체).
 *
 * @param branchNo 조문가지번호 (제10조의2 → 2, 없으면 0)
 * @param content  항 내용 + 그 항의 호·목 내용을 줄바꿈으로 이어 붙인 것
 */
public record ParsedArticle(
        int articleNo,
        int branchNo,
        Integer paragraphNo,
        String title,
        String content,
        LocalDate effectiveFrom
) {
}
