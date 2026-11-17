package com.team.docai.domain.law.dto;

import java.time.Duration;
import java.util.List;

/**
 * 적재 결과.
 *
 * @param documentTypeLaws 문서 종류별 관련 법령 시드 결과 (INSERTED / EXISTS / NO_LAW / SKIPPED_NO_DOCUMENT_TYPE)
 * @param apiCalls  실제로 보낸 HTTP 요청 수 (재시도 포함)
 * @param cacheHits 캐시로 대신한 응답 수
 */
public record LawLoadReport(
        List<LawResult> laws,
        int aliases,
        List<String> documentTypeLaws,
        List<String> ruleParams,
        int apiCalls,
        int cacheHits,
        Duration elapsed,
        String cacheDir
) {
    public enum Status { LOADED, UNCHANGED, NOT_FOUND, FAILED }

    /**
     * @param mst          법령일련번호
     * @param articleRows  이 개정본으로 DB 에 있는 행 수
     * @param inserted     이번에 새로 넣은 행 수
     * @param updated      이번에 내용을 고친 행 수 (강제 갱신)
     * @param closed       다른 개정본에서 닫은 행 수
     * @param skipped      저장하지 않은 것 (삭제 조문·삭제 항·장절 제목)
     */
    public record LawResult(String name, Status status, String mst, long articleRows, int inserted, int updated,
                            int closed, String skipped, String message) {
    }

    public long loadedCount() {
        return laws.stream().filter(l -> l.status() == Status.LOADED || l.status() == Status.UNCHANGED).count();
    }
}
