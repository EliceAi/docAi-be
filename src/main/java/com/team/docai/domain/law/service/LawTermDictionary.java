package com.team.docai.domain.law.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.law.dto.DailyTermDictionaryFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 일상용어 → 법령용어 사전으로 검색어 넓히기 (근거 찾기 전문 검색, 챗봇 조문 검색).
 * 사전은 서버 리소스 law/daily-terms.json (LawTermDictionaryBuilder 가 미리 만든 것, docai.law.terms-resource 로 바꿀 수 있음).
 * 실행 중 외부 API 를 부르지 않는다. 파일이 없거나 깨졌으면 경고만 남기고 빈 사전으로 뜬다 (검색은 넓히지 않고 원래대로).
 * <ul>
 *   <li>원래 검색어는 그대로 두고 앞에 둔다. 더한 법령용어는 뒤에 붙인다 (검색 상한·순서 규칙은 LawSearchService 그대로).</li>
 *   <li>맞추기: 검색어의 낱말이 일상용어와 같거나, 일상용어 뒤에 흔한 접미사·조사만 붙었으면
 *       ("알바는", "월세를", "알바생도"는 맞고 "시급히", "보증금반환"은 안 맞음, {@link #TAIL}).
 *       띄어 쓴 일상용어("계약 해지")는 검색어의 글자·숫자만 이어 붙인 글에 들어 있으면.</li>
 *   <li>이미 검색어에 있는 법령용어는 다시 넣지 않는다. 더하는 수는 {@link #MAX_ADDED}개까지.</li>
 * </ul>
 */
@Slf4j
@Component
public class LawTermDictionary {

    static final String RESOURCE = "law/daily-terms.json";
    /** 한 번 검색에 더하는 법령용어 수 상한. 검색어 수 상한(docai.law.search-max-keywords)이 원래 검색어를 밀어내지 않게 뒤에 붙인다. */
    static final int MAX_ADDED = 5;

    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    /**
     * 일상용어 뒤에 붙어도 같은 뜻으로 보는 꼬리: 접미사(생·님·수당·비·료·금·들) 0~1개 + 조사 0~2개.
     * 그 밖의 글자가 붙으면 다른 낱말로 본다 (시급 + 히 = 시급히).
     */
    static final Pattern TAIL = Pattern.compile(
            "^(생|님|수당|비|료|금|들)?(은|는|이|가|을|를|에|의|도|만|으로|로|과|와|랑|이랑|하고|에서|에게|까지|부터|보다|처럼|이나|나|요|이요|이고|이면|이라|인데){0,2}$");

    /** 키: 일상용어(글자·숫자만, 소문자), 값: 법령용어 (우선순위 순). 띄어 쓴 일상용어는 multiWord 에 */
    private final Map<String, List<String>> single;
    private final Map<String, List<String>> multiWord;

    @Autowired
    public LawTermDictionary(ObjectMapper objectMapper,
                             @Value("${docai.law.terms-resource:" + RESOURCE + "}") String resource) {
        this(read(objectMapper, resource));
    }

    /** @param terms 일상용어 → 법령용어 (테스트·직접 구성용) */
    public LawTermDictionary(Map<String, List<String>> terms) {
        Map<String, List<String>> s = new LinkedHashMap<>();
        Map<String, List<String>> m = new LinkedHashMap<>();
        terms.forEach((daily, legal) -> {
            String key = compact(daily);
            if (key.isEmpty() || legal == null || legal.isEmpty()) {
                return;
            }
            (daily.trim().contains(" ") ? m : s).put(key, List.copyOf(legal));
        });
        this.single = Map.copyOf(s);
        this.multiWord = Map.copyOf(m);
    }

    public int size() {
        return single.size() + multiWord.size();
    }

    /** 원래 검색어 + 더한 법령용어. 사전에 맞는 말이 없으면 원래 목록을 그대로 (새 목록으로) 돌려준다. */
    public List<String> expand(List<String> keywords) {
        List<String> out = new ArrayList<>();
        if (keywords != null) {
            keywords.stream().filter(k -> k != null).forEach(out::add);
        }
        out.addAll(added(keywords));
        return List.copyOf(out);
    }

    /** 검색어에 없는, 더할 법령용어만 (우선순위 순, {@link #MAX_ADDED}개까지). */
    public List<String> added(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        Set<String> present = new LinkedHashSet<>();
        keywords.stream().filter(k -> k != null).forEach(k -> present.add(compact(k)));
        List<String> added = new ArrayList<>();
        for (String k : keywords) {
            if (k == null) {
                continue;
            }
            for (String legal : lookup(k)) {
                if (added.size() >= MAX_ADDED) {
                    return List.copyOf(added);
                }
                if (present.add(compact(legal))) {
                    added.add(legal);
                }
            }
        }
        return List.copyOf(added);
    }

    private List<String> lookup(String keyword) {
        List<String> out = new ArrayList<>();
        for (String token : SPLIT.split(keyword.toLowerCase(Locale.ROOT))) {
            if (token.isEmpty()) {
                continue;
            }
            single.forEach((daily, legal) -> {
                if (token.startsWith(daily) && TAIL.matcher(token.substring(daily.length())).matches()) {
                    out.addAll(legal);
                }
            });
        }
        String whole = compact(keyword);
        multiWord.forEach((daily, legal) -> {
            if (whole.contains(daily)) {
                out.addAll(legal);
            }
        });
        return out;
    }

    private static String compact(String s) {
        return s == null ? "" : SPLIT.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * @param resourcePath 클래스패스 상대 경로만 받는다 (docai.law.terms-resource, 예: law/daily-terms.json).
     *                     파일 시스템 경로·"classpath:" 접두어·URL 은 쓰지 않는다 (ClassPathResource 로 읽음)
     */
    static Map<String, List<String>> read(ObjectMapper objectMapper, String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            log.warn("일상용어 사전 {} 이 없어 검색어를 넓히지 않습니다.", resourcePath);
            return Map.of();
        }
        try (InputStream in = resource.getInputStream()) {
            DailyTermDictionaryFile file = objectMapper.readValue(in, DailyTermDictionaryFile.class);
            Map<String, List<String>> out = new LinkedHashMap<>();
            if (file != null && file.terms() != null) {
                for (DailyTermDictionaryFile.Entry e : file.terms()) {
                    if (e != null && e.daily() != null && e.legal() != null) {
                        out.put(e.daily(), e.legal().stream().filter(l -> l != null).map(DailyTermDictionaryFile.Legal::term)
                                .filter(t -> t != null && !t.isBlank()).toList());
                    }
                }
            }
            log.info("일상용어 사전 {}개를 읽음 ({})", out.size(), file == null ? null : file.generatedAt());
            return out;
        } catch (IOException | RuntimeException e) {
            log.warn("일상용어 사전 {} 을 읽지 못해 검색어를 넓히지 않습니다: {}", resourcePath, e.getMessage());
            return Map.of();
        }
    }
}
