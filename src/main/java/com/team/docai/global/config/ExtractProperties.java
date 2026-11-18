package com.team.docai.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yml 의 docai.analysis.extract 구역 (분석 1차: 사진 → 조항·주요 조건).
 *
 * @param enabled     false 면 1차 단계 빈을 만들지 않는다 (가짜 단계로 실행 뼈대만 보는 테스트용)
 * @param concurrency 1차 묶음 호출을 서버 전체에서 동시에 몇 개까지 보낼지. 분석 전용 스레드 풀(executor-threads)과 따로 센다
 * @param queueCapacity 동시 호출이 모두 바쁠 때 기다리게 하는 묶음 수
 */
@ConfigurationProperties(prefix = "docai.analysis.extract")
public record ExtractProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3") int concurrency,
        @DefaultValue("200") int queueCapacity
) {
    public ExtractProperties {
        if (concurrency < 1 || queueCapacity < 0) {
            throw new IllegalArgumentException("docai.analysis.extract 의 concurrency 는 1 이상, queue-capacity 는 0 이상이어야 합니다.");
        }
    }
}
