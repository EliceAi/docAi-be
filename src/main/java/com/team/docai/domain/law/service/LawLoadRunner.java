package com.team.docai.domain.law.service;

import com.team.docai.global.config.LawProperties;
import com.team.docai.domain.law.dto.LawLoadReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * docai.law.load=true 로 켰을 때만 기동 직후 법령 적재를 1회 돌린다. 기본은 꺼져 있어 서버 기동·스케줄로는 돌지 않는다.
 * 실행: {@code ./gradlew bootRun --args='--docai.law.load=true --spring.main.web-application-type=none'}
 * docai.law.exit-after-load=true(기본)면 끝난 뒤 프로세스를 끝낸다 (성공 0, 실패 1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "docai.law", name = "load", havingValue = "true")
public class LawLoadRunner implements ApplicationRunner {

    private final LawLoader lawLoader;
    private final ApplicationContext context;
    private final LawProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        int code = 0;
        try {
            LawLoadReport report = lawLoader.load();
            report.laws().forEach(l -> log.info("법령 적재 결과: {}", l));
            report.documentTypeLaws().forEach(d -> log.info("문서별 관련 법령: {}", d));
            log.info("약칭 {}개, 숫자 규칙 {}, API 호출 {}회, 캐시 {}회, {}ms, 캐시 폴더 {}", report.aliases(),
                    report.ruleParams(), report.apiCalls(), report.cacheHits(), report.elapsed().toMillis(), report.cacheDir());
        } catch (RuntimeException e) {
            log.error("법령 적재 실패: {}", e.getMessage());
            code = 1;
        }
        if (properties.exitAfterLoad()) {
            int exit = code;
            System.exit(SpringApplication.exit(context, () -> exit));
        }
    }
}
