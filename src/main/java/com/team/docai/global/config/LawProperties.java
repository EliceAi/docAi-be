package com.team.docai.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * application.yml 의 docai.law 구역 (법령 적재기·조문 검색). 값이 없으면 아래 기본값.
 * 인증값(OC)은 여기 두지 않고 기존 law-api.key(LAW_API_KEY)를 읽는다.
 *
 * @param load                   true 일 때만 기동 시 적재기를 1회 돌린다 (LawLoadRunner). 기본 false
 * @param exitAfterLoad          적재 후 프로세스를 끝낸다
 * @param forceRefresh           본문 캐시를 무시하고 다시 받으며, DB 에 같은 개정본이 있어도 다시 저장한다 (offline 이면 캐시로 다시 저장)
 * @param offline                API 를 부르지 않고 캐시(목록·본문)만 쓴다. 캐시가 없으면 그 법령은 실패. 기본은 목록을 매번 API 로 받는다
 * @param baseUrl                법제처 DRF 주소
 * @param minInterval            API 호출 사이 최소 간격 (동시 호출 없음)
 * @param retryDelay             오류 후 1회 재시도 전 대기 (minInterval 보다 길게)
 * @param connectTimeout         연결 시간 제한
 * @param readTimeout            응답 시간 제한
 * @param listDisplay            목록 조회 한 쪽 결과 수 (API 최대 100)
 * @param listMaxPages           목록에서 법령을 못 찾았을 때 넘겨 볼 최대 쪽 수
 * @param maxConsecutiveFailures 법령이 연속으로 이만큼 실패하면 적재를 중단한다
 * @param cacheDir               응답 캐시 폴더. 비면 {STORAGE_ROOT 또는 ./storage}/law-cache
 * @param searchDefaultLimit     조문 검색 기본 결과 수 (아키텍처 6단계: 조항당 최대 5개)
 * @param searchMaxLimit         조문 검색 결과 상한
 * @param searchMinScore         전문 검색 점수(ts_rank)가 이보다 낮은 조문은 뺀다
 * @param searchMaxKeywords      검색에 쓰는 키워드 수 상한 (넘는 것은 버림)
 * @param searchMaxQueryLength   만든 tsquery 글자 수 상한 (넘기 전까지의 키워드만 씀)
 */
@ConfigurationProperties(prefix = "docai.law")
public record LawProperties(
        @DefaultValue("false") boolean load,
        @DefaultValue("true") boolean exitAfterLoad,
        @DefaultValue("false") boolean forceRefresh,
        @DefaultValue("false") boolean offline,
        @DefaultValue("https://www.law.go.kr/DRF") String baseUrl,
        @DefaultValue("1s") Duration minInterval,
        @DefaultValue("3s") Duration retryDelay,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout,
        @DefaultValue("100") int listDisplay,
        @DefaultValue("3") int listMaxPages,
        @DefaultValue("3") int maxConsecutiveFailures,
        @DefaultValue("") String cacheDir,
        @DefaultValue("5") int searchDefaultLimit,
        @DefaultValue("20") int searchMaxLimit,
        @DefaultValue("0") double searchMinScore,
        @DefaultValue("10") int searchMaxKeywords,
        @DefaultValue("500") int searchMaxQueryLength
) {
    /** 실제 적재(법제처 호출) 때 허용하는 최소 호출 간격. 테스트는 load(...) 오버로드로 더 짧게 쓸 수 있다. */
    public static final java.time.Duration REAL_MIN_INTERVAL = java.time.Duration.ofSeconds(1);

    public LawProperties {
        if (minInterval == null || minInterval.isNegative()) {
            throw new IllegalArgumentException("docai.law.min-interval 은 0 이상이어야 합니다.");
        }
        if (retryDelay == null || retryDelay.compareTo(minInterval) < 0) {
            throw new IllegalArgumentException("docai.law.retry-delay 는 min-interval 이상이어야 합니다.");
        }
        if (listDisplay < 1 || listDisplay > 100 || listMaxPages < 1 || maxConsecutiveFailures < 1) {
            throw new IllegalArgumentException("docai.law 목록·실패 설정값이 올바르지 않습니다.");
        }
        if (searchMaxKeywords < 1 || searchMaxQueryLength < 10) {
            throw new IllegalArgumentException("docai.law.search-max-keywords / search-max-query-length 설정값이 올바르지 않습니다.");
        }
        if (searchDefaultLimit < 1 || searchMaxLimit < searchDefaultLimit) {
            throw new IllegalArgumentException("docai.law.search-*-limit 설정값이 올바르지 않습니다.");
        }
    }
}
