package com.team.docai.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 백그라운드 분석 실행 설정.
 * <ul>
 *   <li>분석 파이프라인은 전용 스레드 풀(analysisExecutor)에서 돈다. 요청 스레드·다른 비동기 작업과 섞이지 않게.
 *       풀과 대기열이 모두 차면 거절하고(요청 스레드에서 대신 돌리지 않음), AnalysisPipelineLauncher 가 바로 FAILED 로 바꾼다.</li>
 *   <li>{@code @EnableScheduling} 은 조건 없이 켠다 (앱의 다른 {@code @Scheduled} 용). 분석의 heartbeat·멈춘 분석 정리는
 *       {@code @Scheduled} 를 쓰지 않고 AnalysisLivenessScheduler 가 가진 전용 스케줄러에서 돌아 다른 작업과 스레드를 나누지 않는다.
 *       그 스케줄러는 TaskScheduler 빈으로 등록하지 않는다 (빈이면 {@code @Scheduled} 기본 스케줄러로 잡혀 함께 쓰이게 됨).</li>
 *   <li>analysisExecutor 빈(Executor)이 있으므로 Spring Boot 기본 {@code applicationTaskExecutor} 는 만들어지지 않는다
 *       (Boot 3.5 TaskExecutorConfigurations 의 OnExecutorCondition: Executor 빈이 없거나
 *       {@code spring.task.execution.mode=force} 일 때만 만듦). 그래서 나중에 {@code @EnableAsync} 를 켜고 이름 없이
 *       {@code @Async} 를 쓰면 하나뿐인 TaskExecutor 인 analysisExecutor 로 가서 분석과 스레드를 나눠 쓰게 된다.
 *       다른 비동기 작업이 생기면 자기 Executor 빈을 이름으로 지정하거나 mode=force 로 기본 풀을 되살린다.</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AnalysisProperties.class)
public class AnalysisAsyncConfig {

    public static final String ANALYSIS_EXECUTOR = "analysisExecutor";

    @Bean(name = ANALYSIS_EXECUTOR)
    public ThreadPoolTaskExecutor analysisExecutor(AnalysisProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.executorThreads());
        executor.setMaxPoolSize(properties.executorThreads());
        executor.setQueueCapacity(properties.executorQueueCapacity());
        executor.setThreadNamePrefix("analysis-");
        return executor;
    }
}
