package com.team.docai.domain.law.client;

import java.time.LocalDate;

/**
 * 현행법령 목록의 한 줄.
 *
 * @param mst            법령일련번호 (= 본문 조회의 MST, 개정마다 바뀜)
 * @param sourceLawId    법령ID (개정돼도 같음)
 * @param name           법령명한글
 * @param abbreviation   법령약칭명 (없으면 null)
 * @param kind           법령구분명 (법률 / 대통령령 ...)
 * @param currentCode    현행연혁코드 (현행 ...)
 */
public record LawListItem(
        String mst,
        String sourceLawId,
        String name,
        String abbreviation,
        String kind,
        String currentCode,
        LocalDate promulgatedAt,
        LocalDate effectiveDate
) {
}
