package com.team.docai.domain.law.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.team.docai.domain.law.client.DailyTermLink;
import com.team.docai.domain.law.client.LawApiCache;
import com.team.docai.domain.law.client.LawApiClient;
import com.team.docai.domain.law.client.LawTermResponseParser;
import com.team.docai.domain.law.dto.DailyTermDictionaryFile;
import com.team.docai.domain.law.dto.DailyTermDictionaryFile.Entry;
import com.team.docai.domain.law.dto.DailyTermDictionaryFile.Legal;
import com.team.docai.domain.law.dto.DailyTermDictionaryFile.Rejected;
import com.team.docai.domain.law.entity.LawNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 일상용어 → 법령용어 사전 만들기 (미리 적재. 분석·챗봇 중에는 외부 API 를 부르지 않는다).
 * LawTermLoadRunner(docai.law.terms-load=true)나 테스트가 직접 부른다. API 를 부르므로 트랜잭션을 붙이지 않는다.
 * <ol>
 *   <li>{@link LawTermCatalog#DAILY_TERMS} 마다 법제처 일상용어-법령용어 연계(dlytrmRlt)를 받는다.
 *       응답은 캐시(daily-term/{일상용어}.json)에 두고, 캐시가 있으면 다시 부르지 않는다(force 면 다시). offline 이면 캐시만.</li>
 *   <li>걸러 낸다 ({@link #RULES}): 반의어, 일상용어와 같은 말, 한 글자, 일상용어로 시작하는 말, 뜻이 넓은 일반 낱말, 사람이 뺀 연결,
 *       관련 법령(문서 종류별 관련 법령 전체)의 시행 중 조문에 한 번도 안 나오는 말(검색에 도움이 안 됨),
 *       {@link LawTermCatalog#MAX_ROWS}행보다 많이 나오는 흔한 말.</li>
 *   <li>남은 것을 용어관계(동의어 → 하위어 → 상위어 → 연관어) 순으로 {@link LawTermCatalog#MAX_LEGAL_PER_DAILY}개까지.
 *       이미 고른 법령용어로 시작하는 말(단시간근로 → 단시간근로자)은 검색이 이미 찾으므로 뺀다.</li>
 *   <li>결과를 JSON 파일로 쓴다 (서버는 src/main/resources/law/daily-terms.json 을 읽는다). 걸러 낸 것도 이유와 함께 남긴다.</li>
 * </ol>
 */
@Slf4j
@Service
public class LawTermDictionaryBuilder {

    static final String SOURCE = "법제처 Open API 지능형 법령정보지식베이스 일상용어-법령용어 연계 (lawService.do target=dlytrmRlt)";
    static final List<String> RULES = List.of(
            "반의어(140302)는 쓰지 않는다 (ANTONYM)",
            "일상용어와 같은 말, 한 글자 낱말은 뺀다 (SAME_AS_DAILY, TOO_SHORT)",
            "일상용어로 시작하는 말(보증금 → 보증금등)은 일상용어 앞부분 일치 검색이 이미 찾으므로 뺀다 (COVERED_BY_DAILY)",
            "뜻이 넓은 일반 낱말은 뺀다 (GENERIC_WORD: LawTermCatalog.GENERIC_WORDS, 동음이의어로 잘못 연결되는 경우가 많음)",
            "사람이 검토해 뺀 연결 (BLOCKED_PAIR: LawTermCatalog.BLOCKED_PAIRS)",
            "근로·임대차 관련 법령의 시행 중 조문에 한 번도 검색되지 않는 말은 뺀다 (NOT_IN_LAW_DB)",
            "관련 법령 조문 " + LawTermCatalog.MAX_ROWS + "행보다 많이 검색되는 흔한 말은 뺀다 (TOO_COMMON)",
            "남은 것은 동의어 → 하위어 → 상위어 → 연관어 순으로 고르고, 이미 고른 법령용어로 시작하는 말은 앞부분 일치 검색이 이미 찾으므로 뺀다 (COVERED_BY_LEGAL)",
            "일상용어당 " + LawTermCatalog.MAX_LEGAL_PER_DAILY + "개까지 (OVER_LIMIT)");
    static final List<String> DOCUMENT_TYPES = List.of("EMPLOYMENT", "LEASE");

    private final LawApiClient client;
    private final LawApiCache cache;
    private final LawTermResponseParser parser;
    private final LawSearchService lawSearchService;
    private final ObjectMapper objectMapper;

    public LawTermDictionaryBuilder(LawApiClient client, LawApiCache cache, LawTermResponseParser parser,
                                    LawSearchService lawSearchService, ObjectMapper objectMapper) {
        this.client = client;
        this.cache = cache;
        this.parser = parser;
        this.lawSearchService = lawSearchService;
        this.objectMapper = objectMapper;
    }

    /** 빌드 결과와 호출 수. */
    public record BuildReport(DailyTermDictionaryFile dictionary, int apiCalls, int cacheHits) {
    }

    /** 실제 적재: 카탈로그의 일상용어 전체. */
    public BuildReport build(boolean force, boolean offline) {
        Set<Long> lawIds = new LinkedHashSet<>();
        DOCUMENT_TYPES.forEach(t -> lawIds.addAll(lawSearchService.findLawIds(t)));
        if (lawIds.isEmpty()) {
            throw new IllegalStateException("문서별 관련 법령(document_type_law)이 비어 있어 사전을 만들 수 없습니다. 법령 적재를 먼저 하세요.");
        }
        return build(LawTermCatalog.DAILY_TERMS, lawIds, LawTermCatalog.BLOCKED_PAIRS, force, offline);
    }

    public BuildReport build(List<String> dailyTerms, Collection<Long> lawIds, Map<String, Set<String>> blockedPairs,
                             boolean force, boolean offline) {
        int callsAtStart = client.callCount();
        int cacheHits = 0;
        List<Entry> entries = new ArrayList<>();
        List<String> noLinks = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (String daily : dailyTerms) {
            Optional<String> cached = force && !offline ? Optional.empty() : cache.readDailyTerm(daily);
            String body;
            if (cached.isPresent()) {
                cacheHits++;
                body = cached.get();
            } else if (offline) {
                throw new IllegalStateException("offline 인데 일상용어 캐시가 없음: " + daily);
            } else {
                body = client.fetchDailyTermLinks(daily);
                cache.writeDailyTerm(daily, body);
            }
            List<DailyTermLink> links = parser.parse(body);
            if (links.isEmpty()) {
                noLinks.add(daily);
                continue;
            }
            List<Legal> kept = new ArrayList<>();
            List<DailyTermLink> keptLinks = new ArrayList<>();
            for (DailyTermLink link : links) {
                String reason = basicReject(daily, link, blockedPairs);
                long rows = 0;
                if (reason == null) {
                    rows = lawSearchService.countCurrentArticles(link.legalTerm(), lawIds);
                    if (rows == 0) {
                        reason = "NOT_IN_LAW_DB";
                    } else if (rows > LawTermCatalog.MAX_ROWS) {
                        reason = "TOO_COMMON";
                    }
                }
                if (reason != null) {
                    rejected.add(new Rejected(daily, link.legalTerm(), link.relation(), reason));
                    continue;
                }
                kept.add(new Legal(link.legalTerm(), link.relation(), rows));
                keptLinks.add(link);
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingInt(i -> LawTermCatalog.relationPriority(keptLinks.get(i).relationCode())));
            List<Legal> chosen = new ArrayList<>();
            for (int i : order) {
                Legal candidate = kept.get(i);
                String norm = LawNames.normalize(candidate.term());
                if (chosen.stream().anyMatch(c -> norm.startsWith(LawNames.normalize(c.term())))) {
                    rejected.add(new Rejected(daily, candidate.term(), candidate.relation(), "COVERED_BY_LEGAL"));
                    continue;
                }
                // 뒤에 온 짧은 말이 이미 고른 긴 말을 덮으면(앞부분 일치로 찾음) 긴 말을 빼고 짧은 말을 남긴다
                List<Legal> covered = chosen.stream()
                        .filter(c -> LawNames.normalize(c.term()).startsWith(norm)).toList();
                for (Legal c : covered) {
                    chosen.remove(c);
                    rejected.add(new Rejected(daily, c.term(), c.relation(), "COVERED_BY_LEGAL"));
                }
                if (chosen.size() < LawTermCatalog.MAX_LEGAL_PER_DAILY) {
                    chosen.add(candidate);
                } else {
                    rejected.add(new Rejected(daily, candidate.term(), candidate.relation(), "OVER_LIMIT"));
                }
            }
            if (!chosen.isEmpty()) {
                entries.add(new Entry(daily, List.copyOf(chosen)));
            }
        }
        DailyTermDictionaryFile file = new DailyTermDictionaryFile(SOURCE, LocalDate.now().toString(), RULES,
                List.copyOf(entries), List.copyOf(noLinks), List.copyOf(rejected));
        int calls = client.callCount() - callsAtStart;
        log.info("일상용어 사전: 일상용어 {}개, 채택 {}개, 연계 없음 {}개, 걸러 냄 {}개, API 호출 {}회, 캐시 {}회",
                dailyTerms.size(), entries.size(), noLinks.size(), rejected.size(), calls, cacheHits);
        return new BuildReport(file, calls, cacheHits);
    }

    /** DB 를 보지 않고 거를 수 있는 이유. 통과면 null. */
    static String basicReject(String daily, DailyTermLink link, Map<String, Set<String>> blockedPairs) {
        String term = link.legalTerm();
        if (DailyTermLink.ANTONYM.equals(link.relationCode())) {
            return "ANTONYM";
        }
        String norm = LawNames.normalize(term);
        if (norm == null || norm.equals(LawNames.normalize(daily))) {
            return "SAME_AS_DAILY";
        }
        if (norm.codePointCount(0, norm.length()) < 2) {
            return "TOO_SHORT";
        }
        if (norm.startsWith(LawNames.normalize(daily))) {
            return "COVERED_BY_DAILY";
        }
        if (LawTermCatalog.GENERIC_WORDS.contains(term)) {
            return "GENERIC_WORD";
        }
        if (blockedPairs.getOrDefault(daily, Set.of()).contains(term)) {
            return "BLOCKED_PAIR";
        }
        return null;
    }

    /** 사전 파일 쓰기 (UTF-8, 들여쓰기). */
    public void write(DailyTermDictionaryFile file, Path output) {
        try {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            String json = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(file);
            Files.writeString(output, json + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("일상용어 사전 파일을 쓰지 못했습니다: " + output, e);
        }
    }
}
