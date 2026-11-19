package com.team.docai.domain.law.service;

import com.team.docai.domain.law.client.LawApiCache;
import com.team.docai.domain.law.client.LawApiClient;
import com.team.docai.domain.law.client.LawListItem;
import com.team.docai.domain.law.client.LawListPage;
import com.team.docai.domain.law.client.LawResponseParser;
import com.team.docai.domain.law.client.ParsedLaw;
import com.team.docai.global.config.LawProperties;
import org.springframework.core.NestedExceptionUtils;
import com.team.docai.domain.law.dto.LawLoadReport;
import com.team.docai.domain.law.dto.LawLoadReport.LawResult;
import com.team.docai.domain.law.dto.LawLoadReport.Status;
import com.team.docai.domain.law.dto.RuleSeed;
import com.team.docai.domain.law.entity.AliasSource;
import com.team.docai.domain.law.entity.LawNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 법제처 현행법령 적재기. 서버 기동·스케줄로 돌지 않고, LawLoadRunner(docai.law.load=true)나 테스트가 직접 부른다.
 * API 를 부르므로 트랜잭션을 붙이지 않는다. 저장은 법령마다 LawWriter 의 짧은 트랜잭션.
 *
 * <ol>
 *   <li>대상 법령의 검색어마다 목록 조회. 목록은 개정을 알아채야 하므로 매번 API 로 받는다(받은 것은 캐시에 기록).
 *       offline=true 일 때만 목록 캐시를 쓴다. 같은 검색어는 한 실행에서 한 번만.</li>
 *   <li>목록에서 이름이 정확히 같은 현행 법령을 고른다. 없으면 다음 쪽(최대 list-max-pages).</li>
 *   <li>DB 에 같은 법령일련번호(MST)의 조문이 있으면 본문을 부르지 않는다 (바뀐 것 없음).</li>
 *   <li>본문 조회 (캐시 있으면 캐시, 키는 MST) → 파싱 → 저장.</li>
 *   <li>실패한 법령은 건너뛰고 기록. 연속 실패가 max-consecutive-failures 에 닿으면 중단.</li>
 *   <li>약칭(API 법령약칭명 + 팀 줄임말), 상위 법령, 문서 종류별 관련 법령, 숫자 규칙 기준값.</li>
 * </ol>
 */
@Slf4j
@Service
public class LawLoader {

    private final LawApiClient client;
    private final LawApiCache cache;
    private final LawResponseParser parser;
    private final LawWriter writer;
    private final LawProperties properties;

    public LawLoader(LawApiClient client, LawApiCache cache, LawResponseParser parser, LawWriter writer,
                     LawProperties properties) {
        this.client = client;
        this.cache = cache;
        this.parser = parser;
        this.writer = writer;
        this.properties = properties;
    }

    /** 실행 1회 동안의 상태 (호출·캐시 수, 이미 받은 목록). */
    private final class Run {
        final boolean force;
        final boolean offline;
        final Map<String, List<LawListPage>> pagesByQuery = new HashMap<>();
        final int callsAtStart = client.callCount();
        int cacheHits;

        Run(boolean force, boolean offline) {
            this.force = force;
            this.offline = offline;
        }

        int apiCalls() {
            return client.callCount() - callsAtStart;
        }
    }

    /** 실제 적재. 호출 간격이 1초 미만이면 법제처를 부르기 전에 거절한다. */
    public LawLoadReport load() {
        if (!properties.offline() && properties.minInterval().compareTo(LawProperties.REAL_MIN_INTERVAL) < 0) {
            throw new IllegalStateException("docai.law.min-interval 은 실제 적재에서 1초 이상이어야 합니다: " + properties.minInterval());
        }
        return load(LawCatalog.TARGETS, LawCatalog.MANUAL_ALIASES, LawCatalog.DOCUMENT_TYPE_LAWS,
                LawCatalog.RULE_SEEDS, properties.forceRefresh(), properties.offline());
    }

    /**
     * 실제 적재를 대상 법령 일부만 (docai.law.only). 이미 적재한 법령의 목록·본문을 다시 받지 않아,
     * 그 사이 개정이 있어도 기존 조문 행(꼬리표 연결 issue_tag_article 이 가리키는 행)을 건드리지 않는다.
     * 약칭·문서별 관련 법령·숫자 규칙 시드는 전체를 다시 넣는다 (이미 있으면 건너뜀).
     *
     * @param onlyNames 법령명 (LawCatalog.TARGETS 의 이름, 띄어쓰기 무시: bootRun --args 가 공백으로 나누므로). 비면 전체
     */
    public LawLoadReport load(Set<String> onlyNames) {
        if (onlyNames == null || onlyNames.isEmpty()) {
            return load();
        }
        Set<String> wanted = new HashSet<>();
        Set<String> known = new HashSet<>();
        LawCatalog.TARGETS.forEach(t -> known.add(LawNames.normalize(t.name())));
        for (String name : onlyNames) {
            String norm = LawNames.normalize(name);
            if (!known.contains(norm)) {
                throw new IllegalArgumentException("docai.law.only 에 적재 대상이 아닌 법령: " + name);
            }
            wanted.add(norm);
        }
        if (!properties.offline() && properties.minInterval().compareTo(LawProperties.REAL_MIN_INTERVAL) < 0) {
            throw new IllegalStateException("docai.law.min-interval 은 실제 적재에서 1초 이상이어야 합니다: " + properties.minInterval());
        }
        List<LawCatalog.Target> targets = LawCatalog.TARGETS.stream().filter(t -> wanted.contains(LawNames.normalize(t.name()))).toList();
        return load(targets, LawCatalog.MANUAL_ALIASES, LawCatalog.DOCUMENT_TYPE_LAWS,
                LawCatalog.RULE_SEEDS, properties.forceRefresh(), properties.offline());
    }

    public LawLoadReport load(List<LawCatalog.Target> targets, Map<String, String> manualAliases,
                              List<RuleSeed> ruleSeeds, boolean force) {
        return load(targets, manualAliases, List.of(), ruleSeeds, force, false);
    }

    public LawLoadReport load(List<LawCatalog.Target> targets, Map<String, String> manualAliases,
                              List<LawCatalog.DocumentTypeLawSeed> documentTypeLaws,
                              List<RuleSeed> ruleSeeds, boolean force, boolean offline) {
        long start = System.nanoTime();
        Run run = new Run(force, offline);
        List<LawResult> results = new ArrayList<>();
        Map<String, String> apiAliases = new HashMap<>();
        int consecutiveFailures = 0;

        for (LawCatalog.Target target : targets) {
            LawResult result;
            try {
                result = loadOne(run, target, apiAliases);
            } catch (RuntimeException e) {
                log.warn("법령 {} 적재 실패, 건너뜀: {}", target.name(), e.getMessage());
                result = new LawResult(target.name(), Status.FAILED, null, 0, 0, 0, 0, null, e.getMessage());
            }
            results.add(result);
            consecutiveFailures = result.status() == Status.FAILED ? consecutiveFailures + 1 : 0;
            if (consecutiveFailures >= properties.maxConsecutiveFailures()) {
                throw new IllegalStateException("법령 적재 중단: 연속 " + consecutiveFailures + "개 실패. 마지막 오류: "
                        + result.message() + " / 지금까지 결과: " + results);
            }
        }
        if (results.stream().noneMatch(r -> r.status() == Status.LOADED || r.status() == Status.UNCHANGED)) {
            throw new IllegalStateException("법령 적재 실패: 저장된 법령이 없습니다. " + results);
        }

        int aliases = 0;
        for (Map.Entry<String, String> e : apiAliases.entrySet()) {
            aliases += writer.upsertAlias(e.getKey(), e.getValue(), AliasSource.API);
        }
        for (Map.Entry<String, String> e : manualAliases.entrySet()) {
            aliases += writer.upsertAlias(e.getKey(), e.getValue(), AliasSource.MANUAL);
        }
        for (LawCatalog.Target target : targets) {
            if (target.parentName() != null) {
                writer.linkParent(target.name(), target.parentName());
            }
        }
        List<String> documentTypeLawResults = seedDocumentTypeLaws(documentTypeLaws);
        List<String> rules = new ArrayList<>();
        for (RuleSeed seed : ruleSeeds) {
            writer.upsertRule(seed).ifPresent(articleId ->
                    rules.add(seed.ruleCode() + " " + seed.validFrom() + " " + seed.value() + " law_article_id=" + articleId));
        }

        LawLoadReport report = new LawLoadReport(results, aliases, documentTypeLawResults, rules, run.apiCalls(), run.cacheHits,
                Duration.ofNanos(System.nanoTime() - start), cache.root().toAbsolutePath().normalize().toString());
        log.info("법령 적재 끝: 호출 {}회, 캐시 {}회, {}ms, 결과 {}", report.apiCalls(), report.cacheHits(),
                report.elapsed().toMillis(), results);
        return report;
    }

    /** 문서 종류별 관련 법령. document_type 행이 없는 종류는 경고하고 그 종류만 건너뛴다. */
    private List<String> seedDocumentTypeLaws(List<LawCatalog.DocumentTypeLawSeed> seeds) {
        List<String> out = new ArrayList<>();
        Set<String> missingTypes = new HashSet<>();
        for (LawCatalog.DocumentTypeLawSeed seed : seeds) {
            String label = seed.documentTypeCode() + " -> " + seed.lawName();
            if (missingTypes.contains(seed.documentTypeCode())) {
                out.add(label + ": SKIPPED_NO_DOCUMENT_TYPE");
                continue;
            }
            try {
                out.add(label + ": " + writer.insertDocumentTypeLaw(seed.documentTypeCode(), seed.lawName(), seed.core()));
            } catch (DataIntegrityViolationException e) {
                if (!isMissingDocumentType(e)) {
                    throw new IllegalStateException("문서별 관련 법령 저장 실패: " + label + " ("
                            + NestedExceptionUtils.getMostSpecificCause(e).getMessage() + ")", e);
                }
                log.warn("document_type {} 행이 없어 문서별 관련 법령 시드를 건너뜀 (document_type 은 B 소유)", seed.documentTypeCode());
                missingTypes.add(seed.documentTypeCode());
                out.add(label + ": SKIPPED_NO_DOCUMENT_TYPE");
            }
        }
        return out;
    }

    /** document_type 을 가리키는 FK 위반일 때만 "그 종류 없음"으로 본다. 그 밖의 무결성 오류는 실패. */
    static boolean isMissingDocumentType(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(DOCUMENT_TYPE_FK);
    }

    static final String DOCUMENT_TYPE_FK = "fk_document_type_law_document_type_code";

    private LawResult loadOne(Run run, LawCatalog.Target target, Map<String, String> apiAliases) {
        Optional<LawListItem> found = findInList(run, target);
        if (found.isEmpty()) {
            return new LawResult(target.name(), Status.NOT_FOUND, null, 0, 0, 0, 0, null, "목록에 같은 이름의 현행 법령이 없음");
        }
        LawListItem item = found.get();
        if (item.abbreviation() != null) {
            apiAliases.put(item.abbreviation(), item.name());
        }
        if (!run.force && writer.isLoaded(item)) {
            writer.markSynced(item, OffsetDateTime.now());
            return new LawResult(item.name(), Status.UNCHANGED, item.mst(), writer.countRows(item), 0, 0, 0, null,
                    "법령일련번호 " + item.mst() + " (시행 " + item.effectiveDate() + ") 이미 저장됨");
        }
        String body = fetchBody(run, item.mst());
        ParsedLaw parsed = parser.parseBody(body);
        if (!LawNames.normalize(parsed.name()).equals(LawNames.normalize(item.name()))) {
            throw new IllegalStateException("본문 법령명이 목록과 다름: " + parsed.name());
        }
        if (parsed.abbreviation() != null) {
            apiAliases.put(parsed.abbreviation(), item.name());
        }
        LawWriter.SaveResult saved = writer.saveVersion(item, parsed, OffsetDateTime.now());
        String skipped = "삭제 조문 " + parsed.deletedArticles() + ", 삭제 항 " + parsed.deletedParagraphs()
                + ", 삭제 호·목 " + parsed.deletedItems() + ", 장·절 제목 " + parsed.headings();
        return new LawResult(item.name(), Status.LOADED, item.mst(), saved.rows(), saved.inserted(), saved.updated(),
                saved.closed(), skipped, "시행 " + parsed.effectiveDate());
    }

    private Optional<LawListItem> findInList(Run run, LawCatalog.Target target) {
        String wanted = LawNames.normalize(target.name());
        List<LawListPage> pages = run.pagesByQuery.computeIfAbsent(target.listQuery(), q -> new ArrayList<>());
        for (int page = 1; page <= properties.listMaxPages(); page++) {
            if (pages.size() < page) {
                pages.add(parser.parseList(fetchList(run, target.listQuery(), page)));
            }
            LawListPage p = pages.get(page - 1);
            Optional<LawListItem> hit = p.items().stream()
                    .filter(i -> wanted.equals(LawNames.normalize(i.name())))
                    .filter(i -> i.currentCode() == null || i.currentCode().contains("현행"))
                    .findFirst();
            if (hit.isPresent()) {
                return hit;
            }
            if ((long) page * properties.listDisplay() >= p.totalCount()) {
                break;
            }
        }
        return Optional.empty();
    }

    /** 목록: 기본은 매번 API (개정 감지). offline 일 때만 캐시, 없으면 실패. */
    private String fetchList(Run run, String query, int page) {
        if (run.offline) {
            run.cacheHits++;
            return cache.readList(query, page)
                    .orElseThrow(() -> new IllegalStateException("offline 인데 목록 캐시가 없음: " + query + " p" + page));
        }
        String body = client.fetchList(query, page, properties.listDisplay());
        cache.writeList(query, page, body);
        return body;
    }

    /** 본문: MST(개정본) 단위라 캐시가 있으면 쓴다. force 면 다시 받고, offline 이면 캐시만. */
    private String fetchBody(Run run, String mst) {
        if (run.offline || !run.force) {
            Optional<String> cached = cache.readBody(mst);
            if (cached.isPresent()) {
                run.cacheHits++;
                return cached.get();
            }
        }
        if (run.offline) {
            throw new IllegalStateException("offline 인데 본문 캐시가 없음: MST " + mst);
        }
        String body = client.fetchBody(mst);
        cache.writeBody(mst, body);
        return body;
    }
}
