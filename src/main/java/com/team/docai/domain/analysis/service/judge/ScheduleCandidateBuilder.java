package com.team.docai.domain.analysis.service.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.CandidateSource;
import com.team.docai.domain.analysis.entity.ScheduleCandidate;
import com.team.docai.global.util.TextTruncator;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1차가 계약서에서 읽은 날짜(analysis.extraction_json 의 dates: [{label, date, clauseKey}])로 일정 후보를 만든다.
 * DB·GPT 를 쓰지 않는 순수 계산.
 * <ul>
 *   <li>source = WRITTEN (계약서에 적힌 날짜). 법정 기한·고시 기준 계산(REVIEW)은 여기서 만들지 않는다.</li>
 *   <li>date_kind 는 ERD 값 중 이름으로 분명히 가릴 수 있는 것만: 계약 시작(CONTRACT_START), 계약 종료(CONTRACT_END),
 *       입주(MOVE_IN). 어느 것에도 맞지 않는 날짜(계약일, 잔금일 등)는 date_kind 를 정할 수 없어 만들지 않는다.</li>
 *   <li>같은 date_kind 에 서로 다른 날짜가 둘 이상이면 conflict_group = date_kind, 모두 처음 선택 안 함
 *       (일정 후보 조회 명세: 충돌하는 날짜는 앱이 고르지 않는다). 그 밖에는 처음 선택.</li>
 *   <li>title 은 1차가 붙인 이름(50자로 자름), location 은 "N쪽 제M조", page_no 는 그 조항의 쪽.</li>
 *   <li>날짜 없는 누락 후보(missing)는 만들지 않는다: 문서 종류별 "있어야 할 날짜" 목록이 명세·ERD 에 정해져 있지 않고,
 *       일정 후보 조회 명세가 "임의로 계산하지 않음"이라 서버가 목록을 지어낼 수 없다.</li>
 *   <li>법정 기한 REVIEW 후보: 숫자 규칙의 DEADLINE finding(due_date 있음)에서 만들 자리지만, 지금 규칙 단계(RuleStepHandler)는
 *       근거 법령이 적재된 기한 규칙이 없어 DEADLINE finding 을 만들지 않는다. 그런 finding 이 생기면 여기에 더한다.</li>
 * </ul>
 */
final class ScheduleCandidateBuilder {

    static final String CONTRACT_START = "CONTRACT_START";
    static final String CONTRACT_END = "CONTRACT_END";
    static final String MOVE_IN = "MOVE_IN";

    record Built(List<ScheduleCandidate> candidates, List<Map<String, Object>> log) {
    }

    private ScheduleCandidateBuilder() {
    }

    /**
     * @param dates        extraction_json 의 dates 배열 (없으면 null)
     * @param clausesByKey 제외되지 않은 조항 (키 → 조항)
     */
    static Built build(Long analysisId, JsonNode dates, Map<String, AnalysisClause> clausesByKey) {
        List<Map<String, Object>> log = new ArrayList<>();
        record Draft(String kind, String title, LocalDate date, String location, Integer pageNo) {
        }
        List<Draft> drafts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (dates != null && dates.isArray()) {
            for (JsonNode d : dates) {
                String label = text(d.path("label"));
                LocalDate date = parse(text(d.path("date")));
                if (label == null || date == null) {
                    continue;
                }
                String kind = kindOf(label);
                if (kind == null) {
                    log.add(JudgeValidator.entry("SCHEDULE", "DATE_KIND_UNKNOWN", "dates",
                            TextTruncator.truncate(label, 50) + " (일정 후보로 만들지 않음)"));
                    continue;
                }
                if (!seen.add(kind + "|" + date)) {
                    continue;
                }
                AnalysisClause clause = clausesByKey.get(text(d.path("clauseKey")));
                String location = null;
                Integer pageNo = null;
                if (clause != null) {
                    pageNo = clause.getPageNo();
                    location = TextTruncator.truncate(clause.getPageNo() + "쪽"
                            + (clause.getArticleNo() == null ? "" : " " + clause.getArticleNo()),
                            ScheduleCandidate.LOCATION_MAX_LENGTH);
                }
                drafts.add(new Draft(kind, TextTruncator.truncate(label, ScheduleCandidate.TITLE_MAX_LENGTH), date,
                        location, pageNo));
            }
        }
        Map<String, Integer> perKind = new LinkedHashMap<>();
        drafts.forEach(d -> perKind.merge(d.kind(), 1, Integer::sum));
        List<ScheduleCandidate> out = new ArrayList<>();
        for (Draft d : drafts) {
            boolean conflict = perKind.get(d.kind()) > 1;
            out.add(ScheduleCandidate.create(analysisId, d.kind(), d.title(), d.date(), CandidateSource.WRITTEN,
                    d.location(), d.pageNo(), conflict ? d.kind() : null, !conflict));
        }
        return new Built(out, log);
    }

    /** 1차가 붙인 날짜 이름으로 date_kind 를 고른다. 분명하지 않으면 null */
    static String kindOf(String label) {
        String l = label.replaceAll("\\s+", "");
        if (l.contains("입주") || l.contains("인도일")) {
            return MOVE_IN;
        }
        if (l.contains("종료") || l.contains("만료") || l.contains("마지막근무")) {
            return CONTRACT_END;
        }
        if ((l.contains("계약") || l.contains("근로") || l.contains("임대차") || l.contains("근무"))
                && (l.contains("시작") || l.contains("개시"))) {
            return CONTRACT_START;
        }
        if (l.contains("입사") || l.contains("첫출근") || l.contains("근무시작")) {
            return CONTRACT_START;
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank() ? null : node.asText().trim();
    }

    private static LocalDate parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
