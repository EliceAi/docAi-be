package com.team.docai.domain.law.client;

import java.util.List;

/** 현행법령 목록 한 쪽. totalCount 는 검색 전체 건수. */
public record LawListPage(int totalCount, List<LawListItem> items) {
}
