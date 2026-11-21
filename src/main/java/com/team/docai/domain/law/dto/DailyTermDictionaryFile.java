package com.team.docai.domain.law.dto;

import java.util.List;

/**
 * 일상용어 → 법령용어 사전 파일 (src/main/resources/law/daily-terms.json) 모양.
 * 적재(LawTermDictionaryBuilder)가 만들고, 서버(LawTermDictionary)는 terms 만 읽는다. rejected·noLinks 는 사람이 검토하려고 남긴다.
 *
 * @param source      출처 설명
 * @param generatedAt 만든 날 (yyyy-MM-dd)
 * @param rules       거른 기준 (사람이 읽는 설명)
 * @param terms       채택한 연결 (일상용어마다 법령용어 목록, 우선순위 순)
 * @param noLinks     법제처에 연계가 없는 일상용어
 * @param rejected    걸러 낸 연결과 이유
 */
public record DailyTermDictionaryFile(String source, String generatedAt, List<String> rules, List<Entry> terms,
                                      List<String> noLinks, List<Rejected> rejected) {

    /** @param legal 채택한 법령용어 (우선순위 순) */
    public record Entry(String daily, List<Legal> legal) {
    }

    /** @param rows 관련 법령의 현행 개정본(시행 예정 포함) 조문 중 이 용어로 검색되는 행 수 */
    public record Legal(String term, String relation, long rows) {
    }

    public record Rejected(String daily, String term, String relation, String reason) {
    }
}
