package com.team.docai.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 분석 1차(묶음별 GPT 호출) 전용 스레드 풀. 분석 파이프라인 풀(analysisExecutor)과 따로 두어,
 * 동시에 도는 분석이 여럿이어도 OpenAI 로 나가는 1차 호출 수가 concurrency 를 넘지 않게 한다.
 * 파이프라인 스레드는 여기에 묶음을 넣고 모두 끝날 때까지 기다린다 (같은 풀이면 서로 기다리다 막힐 수 있음).
 */
@Configuration
@EnableConfigurationProperties(ExtractProperties.class)
public class AnalysisExtractConfig {

    public static final String EXTRACT_EXECUTOR = "analysisExtractExecutor";

    @Bean(name = EXTRACT_EXECUTOR)
    public ThreadPoolTaskExecutor analysisExtractExecutor(ExtractProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.concurrency());
        executor.setMaxPoolSize(properties.concurrency());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("analysis-extract-");
        return executor;
    }
}
