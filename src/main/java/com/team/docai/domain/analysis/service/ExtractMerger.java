package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.ExtractResponse;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.entity.LawHint;
import com.team.docai.global.util.TextTruncator;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 1차 묶음 결과 합치기 (아키텍처 4단계, ERD 설계 결정 '묶음 병합'). DB·GPT 를 쓰지 않는 순수 계산.
 * <ol>
 *   <li>문서 종류: 첫 쪽이 든 묶음이 "다른 종류"라고 하면 TYPE_MISMATCH.</li>
 *   <li>쪽 읽기: UNREADABLE 쪽이 있으면 PAGE_UNREADABLE (가장 앞 쪽 번호). PARTIAL 쪽은 partial_pages.
 *       PARTIAL 인데 못 읽은 곳(unreadableParts)이 비어 있으면 READ 로 낮추고 기록한다 (기울어짐·배경만으로 붙인 PARTIAL 거르기).
 *       못 읽은 곳은 extraction_json 의 pages[].unreadableParts 에만 남는다 (API 응답은 그대로).</li>
 *   <li>조항: 묶음 경계에 걸쳐 둘로 나온 조항(앞 묶음 끝 continuesToNextPage + 뒤 묶음 첫 조항 continuesFromPreviousPage)을
 *       하나로 합치고, 겹친 쪽에서 두 번 나온 조항((쪽, 조 번호, 원문) 같음)은 하나만 남긴다. 키는 GPT 값을 쓰지 않고
 *       쪽·읽은 순서로 c1, c2 ... 를 새로 매긴다. 관련 조항도 새 키로 바꾸고 같은 쌍(방향 무관)은 하나만.</li>
 *   <li>주요 조건: 값 있음이 값 없음보다 우선 (값 없음끼리는 UNREADABLE > MASKED > MISSING). 값이 서로 다르면 앞쪽 값을 쓰고
 *       NEEDS_CHECK, 다른 값은 evidence 에 남김. 조항 키는 새 키로.</li>
 *   <li>길이 (사용자와 정한 규칙): GPT 가 지은 글(oneLine, location, 날짜 이름 등)은 칸 길이로 자른다.
 *       계약서에서 온 값은 자르지 않는다: 주요 조건 value 200자 초과 → 저장 안 함 + NEEDS_CHECK, 조항 title·articleNo 초과 → null,
 *       evidence 300자 초과 → null. original_text 는 TEXT 라 그대로. 모두 validation_log 에 남긴다 (원문 글은 남기지 않음).</li>
 *   <li>꼬리표: 목록에 없는 값은 null + 기록.</li>
 *   <li>내부 키: GPT 가 지은 글(oneLine, location, issueSummary, 날짜 이름)에 든 조항 키(c3 등)는 "관련 조항"으로 바꾼다.
 *       키를 c1 부터 다시 매기므로 글 속 키는 다른 조항을 가리킬 수 있어 이름으로 바꾸지 않는다. 계약서 원문(originalText)과
 *       계약서에서 온 값(title, articleNo, 주요 조건 value·evidence)은 바꾸지 않는다. 바꾼 것은 기록한다.</li>
 * </ol>
 */
final class ExtractMerger {

    static final String STAGE = "EXTRACT";
    /** GPT 가 지은 글 속 조항 키를 바꿀 말 */
    static final String RELATED_CLAUSE = "관련 조항";
    /** 쪽 하나에 남길 못 읽은 곳 설명 최대 개수 (스키마에 maxItems 를 두지 않아 서버에서 자른다) */
    static final int UNREADABLE_PARTS_MAX_ITEMS = 10;
    /** 근로시간 시각 "H:mm"·"HH:mm" ("14시 00분" 같은 글은 GPT 가 HH:mm 으로 옮긴다) */
    private static final java.util.regex.Pattern TIME = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final int MINUTES_PER_DAY = 24 * 60;

    record ChunkResult(int chunkNo, int pageFrom, int pageTo, ExtractResponse response) {
    }

    record Failure(AnalysisFailReason reason, Integer pageNo, String detectedTypeName) {
    }

    record MergedClause(String clauseKey, String articleNo, String title, String originalText, String oneLine,
                        int pageNo, String location, boolean issueFound, String issueSummary, String issueTagCode,
                        List<LawHint> lawHints, List<String> searchKeywords) {
    }

    record Relation(String clauseKey, String relatedClauseKey) {
    }

    record MergedKeyTerm(String label, String value, Map<String, Object> valueJson, KeyTermState state,
                         KeyTermSource source, String clauseKey, String evidence, int sortOrder) {
    }

    /**
     * @param failure      null 이 아니면 분석 실패 (나머지 칸은 비어 있음)
     * @param extraction   analysis.extraction_json (합친 1차 결과)
     * @param clauseIndex  analysis.clause_index_json (2차에 보낼 전체 목차)
     * @param validationLog analysis.validation_log_json 의 1차 기록
     */
    record Result(Failure failure, List<MergedClause> clauses, List<Relation> relations, List<MergedKeyTerm> keyTerms,
                  List<Integer> partialPages, LocalDate lawBaseDate, Map<String, Object> extraction,
                  List<Map<String, Object>> clauseIndex, List<Map<String, Object>> validationLog) {
    }

    private ExtractMerger() {
    }

    static Result merge(String documentTypeCode, List<ChunkResult> chunkResults, Set<String> allowedTags,
                        LocalDate today) {
        List<ChunkResult> chunks = chunkResults.stream()
                .sorted(Comparator.comparingInt(ChunkResult::pageFrom).thenComparingInt(ChunkResult::chunkNo))
                .toList();
        List<Map<String, Object>> log = new ArrayList<>();

        // 1. 쪽 읽기 (못 읽은 쪽이 있으면 종류 판단보다 먼저: 못 읽은 사진으로 "다른 종류"라고 판단할 수 있어서)
        Map<Integer, String> pageStates = new HashMap<>();
        Map<Integer, Set<String>> pageParts = new HashMap<>();
        for (ChunkResult chunk : chunks) {
            for (ExtractResponse.PageRead page : nullSafe(chunk.response().pages())) {
                if (page.pageNo() < chunk.pageFrom() || page.pageNo() > chunk.pageTo()) {
                    log.add(entry("PAGE_OUT_OF_RANGE", "pages", "chunk " + chunk.chunkNo() + " page " + page.pageNo()));
                    continue;
                }
                String target = "pages " + page.pageNo() + ".unreadableParts";
                String readState = page.readState();
                if ("PARTIAL".equals(readState)) {
                    List<String> parts = unreadableParts(page.unreadableParts(), target, log);
                    if (parts.isEmpty()) {
                        // 못 읽은 곳을 대지 못한 PARTIAL (기울어짐·배경만으로 붙인 경우)은 경고하지 않는다
                        log.add(entry("PARTIAL_WITHOUT_PARTS", "pages",
                                "chunk " + chunk.chunkNo() + " page " + page.pageNo() + " (READ 로 낮춤)"));
                        readState = "READ";
                    } else {
                        pageParts.computeIfAbsent(page.pageNo(), k -> new LinkedHashSet<>()).addAll(parts);
                    }
                } else if ("READ".equals(readState)) {
                    long ignored = nullSafe(page.unreadableParts()).stream().filter(s -> !s.isBlank()).count();
                    if (ignored > 0) {
                        log.add(entry("PARTS_IGNORED", target, "READ 쪽의 " + ignored + "개 (저장 안 함)"));
                    }
                }
                pageStates.merge(page.pageNo(), readState, ExtractMerger::worseReadState);
            }
            for (int p = chunk.pageFrom(); p <= chunk.pageTo(); p++) {
                if (!pageStates.containsKey(p)) {
                    log.add(entry("PAGE_STATE_MISSING", "pages", "page " + p + " (READ 로 봄)"));
                    pageStates.put(p, "READ");
                }
            }
        }
        Integer unreadable = pageStates.entrySet().stream().filter(e -> "UNREADABLE".equals(e.getValue()))
                .map(Map.Entry::getKey).min(Integer::compare).orElse(null);
        if (unreadable != null) {
            return failed(new Failure(AnalysisFailReason.PAGE_UNREADABLE, unreadable, null), log);
        }

        // 2. 문서 종류: 첫 쪽이 든 묶음의 판단
        if (!chunks.isEmpty()) {
            ExtractResponse.TypeCheck check = chunks.get(0).response().typeCheck();
            if (check != null && !check.matchesSelected()) {
                return failed(new Failure(AnalysisFailReason.TYPE_MISMATCH, null, check.detectedTypeName()), log);
            }
        }
        List<Integer> partialPages = pageStates.entrySet().stream().filter(e -> "PARTIAL".equals(e.getValue()))
                .map(Map.Entry::getKey).sorted().toList();

        // 3. 조항 합치기
        List<Draft> drafts = new ArrayList<>();
        Map<String, Draft> byLocalKey = new HashMap<>();
        Map<String, Draft> byIdentity = new HashMap<>();
        Draft previousChunkLast = null;
        for (ChunkResult chunk : chunks) {
            List<ExtractResponse.Clause> clauses = nullSafe(chunk.response().clauses());
            Draft lastInChunk = null;
            for (int i = 0; i < clauses.size(); i++) {
                ExtractResponse.Clause c = clauses.get(i);
                int pageNo = c.pageNo();
                if (pageNo < chunk.pageFrom() || pageNo > chunk.pageTo()) {
                    log.add(entry("PAGE_OUT_OF_RANGE", "clause " + c.clauseKey(),
                            "chunk " + chunk.chunkNo() + " page " + pageNo + " → " + chunk.pageFrom()));
                    pageNo = chunk.pageFrom();
                }
                String localKey = localKey(chunk.chunkNo(), c.clauseKey());
                if (i == 0 && c.continuesFromPreviousPage() && previousChunkLast != null
                        && previousChunkLast.continuesToNext && previousChunkLast.chunkNo != chunk.chunkNo()) {
                    previousChunkLast.absorb(chunk.chunkNo(), c);
                    previousChunkLast.continuesToNext = c.continuesToNextPage();
                    byLocalKey.put(localKey, previousChunkLast);
                    log.add(entry("BOUNDARY_MERGED", "clause", "chunk " + previousChunkLast.chunkNo + " + chunk "
                            + chunk.chunkNo() + " (page " + pageNo + ")"));
                    lastInChunk = previousChunkLast;
                    continue;
                }
                String identity = identity(pageNo, c.articleNo(), c.originalText());
                Draft same = identity == null ? null : byIdentity.get(identity);
                if (same != null && same.chunkNo != chunk.chunkNo()) {
                    byLocalKey.put(localKey, same);
                    log.add(entry("DUPLICATE_REMOVED", "clause", "chunk " + chunk.chunkNo() + " page " + pageNo));
                    lastInChunk = same;
                    continue;
                }
                Draft d = new Draft(chunk.chunkNo(), pageNo, drafts.size(), c);
                drafts.add(d);
                if (byLocalKey.putIfAbsent(localKey, d) != null) {
                    log.add(entry("KEY_DUPLICATED", "clause", "chunk " + chunk.chunkNo() + " key " + c.clauseKey()));
                }
                if (identity != null) {
                    byIdentity.putIfAbsent(identity, d);
                }
                lastInChunk = d;
            }
            previousChunkLast = lastInChunk;
        }
        // 쪽 순서, 같은 쪽은 읽은 순서. 키 새로 매김
        drafts.sort(Comparator.comparingInt((Draft d) -> d.pageNo).thenComparingInt(d -> d.order));
        for (int i = 0; i < drafts.size(); i++) {
            drafts.get(i).newKey = "c" + (i + 1);
        }

        List<MergedClause> clauses = new ArrayList<>();
        for (Draft d : drafts) {
            clauses.add(d.toClause(allowedTags, log));
        }

        // 4. 관련 조항 (새 키, 자기 자신·없는 키 제외, 같은 쌍은 하나)
        List<Relation> relations = new ArrayList<>();
        Set<Set<String>> seenPairs = new LinkedHashSet<>();
        for (Draft d : drafts) {
            for (Map.Entry<Integer, String> related : d.relatedLocalKeys) {
                Draft target = byLocalKey.get(localKey(related.getKey(), related.getValue()));
                if (target == null) {
                    log.add(entry("KEY_UNKNOWN", "relatedClauseKeys", d.newKey + " → " + related.getValue()));
                    continue;
                }
                if (target == d) {
                    continue;
                }
                if (seenPairs.add(Set.of(d.newKey, target.newKey))) {
                    relations.add(new Relation(d.newKey, target.newKey));
                }
            }
        }

        // 5. 주요 조건
        List<MergedKeyTerm> keyTerms = mergeKeyTerms(documentTypeCode, chunks, byLocalKey, log);

        // 6. 날짜, 계약일(법 적용 기준일)
        LocalDate contractDate = null;
        String documentTitle = null;
        boolean wageOnlyDocument = false;
        List<Map<String, Object>> dates = new ArrayList<>();
        Set<String> seenDates = new LinkedHashSet<>();
        for (ChunkResult chunk : chunks) {
            ExtractResponse r = chunk.response();
            wageOnlyDocument |= r.wageOnlyDocument();
            if (contractDate == null && r.contractDate() != null) {
                contractDate = parseDate(r.contractDate());
                if (contractDate == null) {
                    log.add(entry("DATE_INVALID", "contractDate", "chunk " + chunk.chunkNo()));
                }
            }
            if (documentTitle == null && r.documentTitle() != null && !r.documentTitle().isBlank()) {
                documentTitle = fromDocument(r.documentTitle(), ExtractPrompt.DOCUMENT_TITLE_MAX, "documentTitle", log);
            }
            for (ExtractResponse.DateItem item : nullSafe(r.dates())) {
                LocalDate date = parseDate(item.date());
                if (date == null) {
                    log.add(entry("DATE_INVALID", "dates", "chunk " + chunk.chunkNo()));
                    continue;
                }
                String label = written(withoutKeys(item.label(), "dates.label", log), ExtractPrompt.DATE_LABEL_MAX,
                        "dates.label", log);
                if (!seenDates.add(label + "|" + date)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", label);
                m.put("date", date.toString());
                m.put("clauseKey", newKeyOf(byLocalKey, chunk.chunkNo(), item.clauseKey(), "dates", log));
                dates.add(m);
            }
        }
        LocalDate lawBaseDate = contractDate != null ? contractDate : today;

        // 7. 저장용 JSON
        List<Map<String, Object>> clauseIndex = new ArrayList<>();
        List<Map<String, Object>> clauseJson = new ArrayList<>();
        Map<String, List<String>> relatedByKey = new LinkedHashMap<>();
        for (Relation r : relations) {
            relatedByKey.computeIfAbsent(r.clauseKey(), k -> new ArrayList<>()).add(r.relatedClauseKey());
        }
        for (MergedClause c : clauses) {
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("clauseKey", c.clauseKey());
            index.put("articleNo", c.articleNo());
            index.put("title", c.title());
            index.put("oneLine", c.oneLine());
            index.put("pageNo", c.pageNo());
            index.put("issueFound", c.issueFound());
            clauseIndex.add(index);

            Map<String, Object> full = new LinkedHashMap<>(index);
            full.put("originalText", c.originalText());
            full.put("location", c.location());
            full.put("issueSummary", c.issueSummary());
            full.put("issueTagCode", c.issueTagCode());
            full.put("lawHints", c.lawHints());
            full.put("searchKeywords", c.searchKeywords());
            full.put("relatedClauseKeys", relatedByKey.getOrDefault(c.clauseKey(), List.of()));
            clauseJson.add(full);
        }
        List<Map<String, Object>> keyTermJson = new ArrayList<>();
        for (MergedKeyTerm t : keyTerms) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", t.label());
            m.put("value", t.value());
            m.put("valueJson", t.valueJson());
            m.put("state", t.state().name());
            m.put("clauseKey", t.clauseKey());
            m.put("evidence", t.evidence());
            keyTermJson.add(m);
        }
        List<Map<String, Object>> pagesJson = new ArrayList<>();
        for (Integer p : new TreeSet<>(pageStates.keySet())) {
            String readState = pageStates.get(p);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pageNo", p);
            m.put("readState", readState);
            List<String> parts = "PARTIAL".equals(readState)
                    ? List.copyOf(pageParts.getOrDefault(p, Set.of())) : List.of();
            if (parts.size() > UNREADABLE_PARTS_MAX_ITEMS) {
                // 겹친 쪽은 여러 묶음 설명을 합친 뒤에 센다
                log.add(entry("TOO_MANY", "pages " + p + ".unreadableParts",
                        parts.size() + "개 > " + UNREADABLE_PARTS_MAX_ITEMS + "개, 앞쪽만 저장"));
                parts = parts.subList(0, UNREADABLE_PARTS_MAX_ITEMS);
            }
            m.put("unreadableParts", parts);
            pagesJson.add(m);
        }
        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("promptVersion", ExtractPrompt.VERSION);
        extraction.put("documentTypeCode", documentTypeCode);
        extraction.put("documentTitle", documentTitle);
        extraction.put("wageOnlyDocument", wageOnlyDocument);
        extraction.put("contractDate", contractDate == null ? null : contractDate.toString());
        extraction.put("pages", pagesJson);
        extraction.put("clauses", clauseJson);
        extraction.put("keyTerms", keyTermJson);
        extraction.put("dates", dates);

        return new Result(null, clauses, relations, keyTerms, partialPages, lawBaseDate, extraction, clauseIndex, log);
    }

    // ---- 주요 조건 ----

    private static List<MergedKeyTerm> mergeKeyTerms(String documentTypeCode, List<ChunkResult> chunks,
                                                     Map<String, Draft> byLocalKey, List<Map<String, Object>> log) {
        List<MergedKeyTerm> result = new ArrayList<>();
        List<ExtractCatalog.Term> terms = ExtractCatalog.terms(documentTypeCode);
        for (int order = 0; order < terms.size(); order++) {
            ExtractCatalog.Term term = terms.get(order);
            ExtractResponse.KeyTerm chosen = null;
            int chosenChunk = 0;
            List<String> otherValues = new ArrayList<>();
            String bestMissingState = null;
            for (ChunkResult chunk : chunks) {
                ExtractResponse.KeyTerm kt = nullSafe(chunk.response().keyTerms()).stream()
                        .filter(k -> term.label().equals(k.label())).findFirst().orElse(null);
                if (kt == null) {
                    continue;
                }
                boolean hasValue = "WRITTEN".equals(kt.state()) && kt.value() != null && !kt.value().isBlank();
                if (!hasValue) {
                    String state = "WRITTEN".equals(kt.state()) ? "MISSING" : kt.state();
                    if ("WRITTEN".equals(kt.state())) {
                        log.add(entry("VALUE_EMPTY", "keyTerm " + term.label(), "WRITTEN 인데 값 없음 → MISSING"));
                    }
                    bestMissingState = betterMissingState(bestMissingState, state);
                    continue;
                }
                if (chosen == null) {
                    chosen = kt;
                    chosenChunk = chunk.chunkNo();
                } else if (!normalize(chosen.value()).equals(normalize(kt.value()))
                        && !otherValues.contains(kt.value())) {
                    otherValues.add(kt.value());
                }
            }
            if (chosen == null) {
                KeyTermState state = KeyTermState.valueOf(bestMissingState == null ? "MISSING" : bestMissingState);
                result.add(new MergedKeyTerm(term.label(), null, null, state, null, null, null, order));
                continue;
            }
            KeyTermState state = KeyTermState.OK;
            // 원문 인용(계약서에서 온 값)만 300자 검사: 넘으면 null. 서버 메모는 남는 길이에 맞춰 붙인다
            String evidence = blankToNull(chosen.evidence());
            if (TextTruncator.exceeds(evidence, AnalysisKeyTerm.EVIDENCE_MAX_LENGTH)) {
                log.add(tooLong("keyTerm " + term.label() + ".evidence", evidence, AnalysisKeyTerm.EVIDENCE_MAX_LENGTH,
                        "null"));
                evidence = null;
            }
            if (!otherValues.isEmpty()) {
                state = KeyTermState.NEEDS_CHECK;
                String note = "다른 쪽에 " + String.join(", ", otherValues.stream().map(v -> "'" + v + "'").toList())
                        + "로도 적혀 있음";
                evidence = appendNote(evidence, note, AnalysisKeyTerm.EVIDENCE_MAX_LENGTH);
                log.add(entry("VALUE_CONFLICT", "keyTerm " + term.label(), otherValues.size() + "개 다른 값"));
            }
            String value = chosen.value();
            if (TextTruncator.exceeds(value, AnalysisKeyTerm.VALUE_MAX_LENGTH)) {
                log.add(tooLong("keyTerm " + term.label() + ".value", value, AnalysisKeyTerm.VALUE_MAX_LENGTH,
                        "저장 안 함, NEEDS_CHECK"));
                value = null;
                state = KeyTermState.NEEDS_CHECK;
            }
            String clauseKey = newKeyOf(byLocalKey, chosenChunk, chosen.clauseKey(), "keyTerm " + term.label(), log);
            result.add(new MergedKeyTerm(term.label(), value, valueJson(term, chosen.valueJson(), log), state,
                    KeyTermSource.WRITTEN, clauseKey, evidence, order));
        }
        return result;
    }

    /** 항목에 정해진 칸만 남긴 value_json. 남는 칸이 없으면 null */
    private static Map<String, Object> valueJson(ExtractCatalog.Term term, ExtractResponse.ValueJson v,
                                                 List<Map<String, Object>> log) {
        if (v == null || term.valueKeys().isEmpty()) {
            return null;
        }
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("type", v.type() != null && ExtractCatalog.WAGE_TYPES.contains(v.type()) ? v.type() : null);
        all.put("amount", v.amount());
        all.put("basePay", v.basePay());
        all.put("hoursPerDay", v.hoursPerDay());
        all.put("daysPerWeek", v.daysPerWeek());
        all.put("monthlyHours", v.monthlyHours());
        all.put("start", dateOrNull(v.start(), term.label() + ".start", log));
        all.put("end", dateOrNull(v.end(), term.label() + ".end", log));
        all.put("workStart", timeOrNull(v.workStart(), term.label() + ".workStart", log));
        all.put("workEnd", timeOrNull(v.workEnd(), term.label() + ".workEnd", log));
        all.put("breaks", breaksOrNull(v.breaks(), term.label() + ".breaks", log));
        all.put("breakMinutes", breakMinutesOrNull(v.breakMinutes(), term.label() + ".breakMinutes", log));
        all.put("noBreak", Boolean.TRUE.equals(v.noBreak()) ? Boolean.TRUE : null);
        Map<String, Object> picked = new LinkedHashMap<>();
        boolean any = false;
        for (String key : term.valueKeys()) {
            Object value = all.get(key);
            if (value == null && ExtractCatalog.OPTIONAL_VALUE_KEYS.contains(key)) {
                continue;
            }
            picked.put(key, value);
            any |= value != null;
        }
        return any ? picked : null;
    }

    /** "H:mm"·"HH:mm" (00:00~24:00) → "HH:mm". 그 밖은 null 로 바꾸고 기록 */
    static String timeOrNull(String value, String target, List<Map<String, Object>> log) {
        if (value == null || value.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = TIME.matcher(value.trim());
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (h < 24 && min < 60 || h == 24 && min == 0) {
                return String.format("%02d:%02d", h, min);
            }
        }
        log.add(entry("TIME_INVALID", "valueJson " + target, "null 로 바꿈"));
        return null;
    }

    /** 휴게 구간: 시각이 둘 다 맞는 것만 남긴다. 남는 것이 없으면 null (키를 넣지 않음) */
    private static List<Map<String, Object>> breaksOrNull(List<ExtractResponse.TimeRange> ranges, String target,
                                                          List<Map<String, Object>> log) {
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (ExtractResponse.TimeRange r : ranges) {
            if (r == null) {
                continue;
            }
            String start = timeOrNull(r.start(), target + ".start", log);
            String end = timeOrNull(r.end(), target + ".end", log);
            if (start == null || end == null || start.equals(end)) {
                log.add(entry("BREAK_INVALID", "valueJson " + target, "구간 버림"));
                continue;
            }
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("start", start);
            range.put("end", end);
            kept.add(range);
        }
        return kept.isEmpty() ? null : kept;
    }

    /** 휴게 분: 1~1440 만. 0 은 "적혀 있지 않음"과 구분되지 않아 버린다 (없음은 noBreak 로 받음) */
    private static Integer breakMinutesOrNull(Integer value, String target, List<Map<String, Object>> log) {
        if (value == null) {
            return null;
        }
        if (value < 1 || value > MINUTES_PER_DAY) {
            log.add(entry("BREAK_INVALID", "valueJson " + target, value + " → null"));
            return null;
        }
        return value;
    }

    private static String dateOrNull(String value, String target, List<Map<String, Object>> log) {
        if (value == null) {
            return null;
        }
        LocalDate date = parseDate(value);
        if (date == null) {
            log.add(entry("DATE_INVALID", "valueJson " + target, "null 로 바꿈"));
            return null;
        }
        return date.toString();
    }

    // ---- 조항 초안 ----

    /** 합치는 동안의 조항. 경계 조항은 뒤 묶음 조각을 흡수한다 */
    private static final class Draft {
        final int chunkNo;
        final int pageNo;
        final int order;
        final ExtractResponse.Clause first;
        String originalText;
        boolean issueFound;
        String issueSummary;
        String issueTag;
        final LinkedHashSet<LawHint> lawHints = new LinkedHashSet<>();
        final LinkedHashSet<String> keywords = new LinkedHashSet<>();
        /** (묶음 번호, 그 묶음의 GPT 키) */
        final List<Map.Entry<Integer, String>> relatedLocalKeys = new ArrayList<>();
        boolean continuesToNext;
        String newKey;

        Draft(int chunkNo, int pageNo, int order, ExtractResponse.Clause c) {
            this.chunkNo = chunkNo;
            this.pageNo = pageNo;
            this.order = order;
            this.first = c;
            this.originalText = blankToNull(c.originalText());
            this.issueFound = c.issueFound();
            this.issueSummary = blankToNull(c.issueSummary());
            this.issueTag = blankToNull(c.issueTag());
            this.continuesToNext = c.continuesToNextPage();
            addLists(chunkNo, c);
        }

        void absorb(int nextChunkNo, ExtractResponse.Clause next) {
            String more = blankToNull(next.originalText());
            if (more != null) {
                originalText = originalText == null ? more : originalText + "\n" + more;
            }
            issueFound |= next.issueFound();
            String summary = blankToNull(next.issueSummary());
            if (summary != null && !summary.equals(issueSummary)) {
                issueSummary = issueSummary == null ? summary : issueSummary + " / " + summary;
            }
            if (issueTag == null) {
                issueTag = blankToNull(next.issueTag());
            }
            addLists(nextChunkNo, next);
        }

        private void addLists(int sourceChunkNo, ExtractResponse.Clause c) {
            for (LawHint h : nullSafe(c.lawHints())) {
                if (h != null && h.law() != null && !h.law().isBlank()) {
                    lawHints.add(new LawHint(h.law().trim(), h.article() == null ? null : h.article().trim()));
                }
            }
            for (String k : nullSafe(c.searchKeywords())) {
                if (k != null && !k.isBlank()) {
                    keywords.add(k.trim());
                }
            }
            // 관련 키는 그 조각을 낸 묶음 안의 GPT 키다
            for (String k : nullSafe(c.relatedClauseKeys())) {
                if (k != null && !k.isBlank()) {
                    relatedLocalKeys.add(Map.entry(sourceChunkNo, k.trim()));
                }
            }
        }

        MergedClause toClause(Set<String> allowedTags, List<Map<String, Object>> log) {
            String target = "clause " + newKey;
            String articleNo = fromDocument(normalizeArticleNo(first.articleNo()), AnalysisClause.ARTICLE_NO_MAX_LENGTH,
                    target + ".articleNo", log);
            String title = fromDocument(first.title(), AnalysisClause.TITLE_MAX_LENGTH, target + ".title", log);
            String oneLine = written(withoutKeys(first.oneLine(), target + ".oneLine", log),
                    AnalysisClause.ONE_LINE_MAX_LENGTH, target + ".oneLine", log);
            String location = written(withoutKeys(first.location(), target + ".location", log),
                    AnalysisClause.LOCATION_MAX_LENGTH, target + ".location", log);
            String summary = withoutKeys(issueSummary, target + ".issueSummary", log);
            String tag = issueTag;
            if (tag != null && !allowedTags.contains(tag)) {
                log.add(entry("TAG_DROPPED", target + ".issueTag",
                        TextTruncator.truncate(tag, AnalysisClause.ISSUE_TAG_CODE_MAX_LENGTH)));
                tag = null;
            }
            return new MergedClause(newKey, articleNo, title, originalText, oneLine, pageNo, location, issueFound,
                    summary, tag, List.copyOf(lawHints), List.copyOf(keywords));
        }
    }

    // ---- 도움 함수 ----

    /** 조 번호의 공백만 없앤다 ("제 10 조 의 2" → "제10조의2"). 다른 글자는 바꾸지 않는다 */
    static String normalizeArticleNo(String articleNo) {
        return articleNo == null ? null : articleNo.replaceAll("\\s+", "");
    }

    /** 원문 인용 뒤에 서버 메모를 붙이되 칸 길이를 넘지 않게 메모를 자른다 (원문 인용은 자르지 않음) */
    static String appendNote(String evidence, String note, int max) {
        if (evidence == null) {
            return TextTruncator.truncate(note, max);
        }
        int left = max - evidence.codePointCount(0, evidence.length()) - 3;
        if (left <= 0) {
            return evidence;
        }
        return evidence + " (" + TextTruncator.truncate(note, left) + ")";
    }

    /** GPT 가 지은 글 속 조항 키 → "관련 조항" (기록). 계약서에서 온 글에는 쓰지 않는다 */
    static String withoutKeys(String value, String target, List<Map<String, Object>> log) {
        return ClauseKeyText.replace(value, k -> RELATED_CLAUSE,
                (found, replaced) -> log.add(entry("CLAUSE_KEY_REPLACED", target, found + " → " + replaced)));
    }

    /** PARTIAL 쪽의 못 읽은 곳 설명 (GPT 가 지은 글): 빈 칸 빼고, 조항 키를 바꾸고, 칸 길이로 자르고, 같은 글은 하나만.
     *  개수 제한은 겹친 쪽을 합친 뒤에 한다 */
    private static List<String> unreadableParts(List<String> raw, String target, List<Map<String, Object>> log) {
        Set<String> parts = new LinkedHashSet<>();
        for (String s : nullSafe(raw)) {
            String v = written(withoutKeys(s == null ? null : s.strip(), target, log),
                    ExtractPrompt.UNREADABLE_PART_MAX, target, log);
            if (v != null) {
                parts.add(v);
            }
        }
        return List.copyOf(parts);
    }

    /** 계약서에서 온 글: 자르지 않고 넘으면 null + 기록 */
    private static String fromDocument(String value, int max, String target, List<Map<String, Object>> log) {
        String v = blankToNull(value);
        if (TextTruncator.exceeds(v, max)) {
            log.add(tooLong(target, v, max, "null"));
            return null;
        }
        return v;
    }

    /** GPT 가 지은 글: 칸 길이로 자르고 기록 */
    private static String written(String value, int max, String target, List<Map<String, Object>> log) {
        String v = blankToNull(value);
        if (TextTruncator.exceeds(v, max)) {
            log.add(tooLong(target, v, max, "잘라 저장"));
            return TextTruncator.truncate(v, max);
        }
        return v;
    }

    private static String newKeyOf(Map<String, Draft> byLocalKey, int chunkNo, String gptKey, String target,
                                   List<Map<String, Object>> log) {
        if (gptKey == null || gptKey.isBlank()) {
            return null;
        }
        Draft d = byLocalKey.get(localKey(chunkNo, gptKey));
        if (d == null) {
            log.add(entry("KEY_UNKNOWN", target, "clauseKey " + TextTruncator.truncate(gptKey, 20)));
            return null;
        }
        return d.newKey;
    }

    private static Result failed(Failure failure, List<Map<String, Object>> log) {
        return new Result(failure, List.of(), List.of(), List.of(), List.of(), null, Map.of(), List.of(), log);
    }

    private static String localKey(int chunkNo, String gptKey) {
        return chunkNo + ":" + (gptKey == null ? "" : gptKey.trim());
    }

    /** 겹친 쪽 중복 판단 키: (쪽, 조 번호, 공백 뺀 원문). 원문이 없으면 판단하지 않는다 */
    private static String identity(int pageNo, String articleNo, String originalText) {
        if (originalText == null || originalText.isBlank()) {
            return null;
        }
        return pageNo + "|" + (articleNo == null ? "" : normalizeArticleNo(articleNo)) + "|"
                + Integer.toHexString(normalize(originalText).hashCode()) + ":" + normalize(originalText).length();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String worseReadState(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(String readState) {
        return switch (readState == null ? "" : readState) {
            case "UNREADABLE" -> 2;
            case "PARTIAL" -> 1;
            default -> 0;
        };
    }

    private static String betterMissingState(String current, String candidate) {
        List<String> priority = List.of("UNREADABLE", "MASKED", "MISSING");
        if (!priority.contains(candidate)) {
            candidate = "MISSING";
        }
        if (current == null) {
            return candidate;
        }
        return priority.indexOf(candidate) < priority.indexOf(current) ? candidate : current;
    }

    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }

    private static Map<String, Object> tooLong(String target, String value, int max, String action) {
        return entry("TOO_LONG", target, value.codePointCount(0, value.length()) + "자 > " + max + "자, " + action);
    }

    private static Map<String, Object> entry(String type, String target, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", STAGE);
        m.put("type", type);
        m.put("target", target);
        m.put("detail", detail);
        return m;
    }
}
