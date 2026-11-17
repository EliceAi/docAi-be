package com.team.docai.domain.law.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.global.config.LawProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 법제처 Open API (현행법령 목록 lawSearch.do, 본문 lawService.do, target=law, type=JSON) 호출.
 * <ul>
 *   <li>한 번에 하나씩: 모든 호출이 이 객체의 잠금 안에서 일어나 동시 호출이 없다.</li>
 *   <li>호출 사이 최소 간격 {@code docai.law.min-interval} (기본 1초). 재시도도 같은 규칙을 따른다.</li>
 *   <li>오류(통신 오류, 4xx·5xx, 기대한 JSON 이 아님)는 {@code retry-delay} 만큼 더 기다렸다가 1회만 재시도. 그래도 실패면 {@link LawApiException}.</li>
 *   <li>인증값(OC)은 로그·예외 메시지·반환 본문 어디에도 남기지 않는다. 목록 응답의 법령상세링크에 OC 가 들어 있으므로 본문에서도 가린다.</li>
 * </ul>
 * 캐시는 이 클래스가 아니라 적재기(LawLoader)가 다룬다. 여기는 부르면 항상 API 를 부른다.
 */
@Slf4j
@Component
public class LawApiClient {

    static final String MASK = "***";
    private static final Pattern OC_PARAM = Pattern.compile("(?i)(OC=)[^&\\s\"']*");
    private static final int MAX_ERROR_CHARS = 300;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final Duration minInterval;
    private final Duration retryDelay;

    private final Object lock = new Object();
    /** 마지막 호출이 끝난 시각 (System.nanoTime). 0 이면 아직 호출 없음 */
    private long lastCallEndNanos;
    private int callCount;

    public LawApiClient(@Qualifier("lawApiRestClient") RestClient restClient, ObjectMapper objectMapper,
                        LawProperties properties, @Value("${law-api.key:}") String apiKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
        this.minInterval = properties.minInterval();
        this.retryDelay = properties.retryDelay();
    }

    /** 현행법령 목록 (법령명 검색). 반환은 OC 를 가린 응답 원본. */
    public String fetchList(String query, int page, int display) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/lawSearch.do")
                .queryParam("OC", apiKey)
                .queryParam("target", "law")
                .queryParam("type", "JSON")
                .queryParam("query", query)
                .queryParam("display", display)
                .queryParam("page", page)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
        return call(uri, "LawSearch");
    }

    /** 현행법령 본문 (MST = 법령일련번호). 반환은 OC 를 가린 응답 원본. */
    public String fetchBody(String mst) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/lawService.do")
                .queryParam("OC", apiKey)
                .queryParam("target", "law")
                .queryParam("type", "JSON")
                .queryParam("MST", mst)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
        return call(uri, "법령");
    }

    /** 이 객체가 실제로 보낸 HTTP 요청 수 (재시도 포함). */
    public int callCount() {
        synchronized (lock) {
            return callCount;
        }
    }

    private String call(URI uri, String expectedRootField) {
        if (apiKey.isEmpty()) {
            throw new LawApiException("법령 API 인증값(LAW_API_KEY)이 비어 있습니다.");
        }
        synchronized (lock) {
            String masked = mask(uri.toString());
            try {
                return attempt(uri, masked, expectedRootField, minInterval);
            } catch (RuntimeException first) {
                log.warn("법령 API 실패, {}ms 뒤 1회 재시도: {} ({})", retryDelay.toMillis(), masked, mask(first.getMessage()));
                try {
                    return attempt(uri, masked, expectedRootField, retryDelay);
                } catch (RuntimeException second) {
                    throw new LawApiException("법령 API 재시도 실패: " + masked + " (" + shorten(mask(second.getMessage())) + ")");
                }
            }
        }
    }

    private String attempt(URI uri, String maskedUrl, String expectedRootField, Duration gap) {
        waitGap(gap);
        long start = System.nanoTime();
        try {
            callCount++;
            String body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
            log.info("법령 API 호출 {} ({}ms)", maskedUrl, (System.nanoTime() - start) / 1_000_000);
            String maskedBody = mask(body);
            validate(maskedBody, expectedRootField);
            return maskedBody;
        } finally {
            lastCallEndNanos = System.nanoTime();
        }
    }

    private void validate(String body, String expectedRootField) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("빈 응답");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 이 아닌 응답: " + shorten(body));
        }
        if (root == null || !root.has(expectedRootField)) {
            throw new IllegalStateException("예상한 응답이 아님: " + shorten(body));
        }
    }

    private void waitGap(Duration gap) {
        if (lastCallEndNanos == 0) {
            return;
        }
        long waitNanos = gap.toNanos() - (System.nanoTime() - lastCallEndNanos);
        if (waitNanos <= 0) {
            return;
        }
        try {
            Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LawApiException("법령 API 대기 중 중단됨");
        }
    }

    /** 인증값과 OC= 파라미터 값을 *** 로 바꾼다. */
    String mask(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        if (!apiKey.isEmpty()) {
            out = out.replace(apiKey, MASK);
        }
        Matcher m = OC_PARAM.matcher(out);
        return m.replaceAll("$1" + Matcher.quoteReplacement(MASK));
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= MAX_ERROR_CHARS ? oneLine : oneLine.substring(0, MAX_ERROR_CHARS) + "...";
    }
}
