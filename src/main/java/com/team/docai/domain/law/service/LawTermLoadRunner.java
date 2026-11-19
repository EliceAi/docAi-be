package com.team.docai.domain.law.service;

import com.team.docai.global.config.LawProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * docai.law.terms-load=true 로 켰을 때만 기동 직후 일상용어 → 법령용어 사전을 1회 만든다 (기본 꺼짐).
 * 실행: {@code ./gradlew bootRun --args='--docai.law.terms-load=true --server.port=0'}
 * 결과는 docai.law.terms-output (기본 src/main/resources/law/daily-terms.json, 저장소 루트에서 실행) 에 쓰고,
 * 사람이 검토해 커밋한다. 서버는 그 파일을 읽기만 한다.
 * docai.law.offline=true 면 캐시만, docai.law.force-refresh=true 면 캐시를 무시하고 다시 받는다.
 * docai.law.exit-after-load=true(기본)면 끝난 뒤 프로세스를 끝낸다 (성공 0, 실패 1).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "docai.law", name = "terms-load", havingValue = "true")
public class LawTermLoadRunner implements ApplicationRunner {

    private final LawTermDictionaryBuilder builder;
    private final ApplicationContext context;
    private final LawProperties properties;
    private final Path output;

    public LawTermLoadRunner(LawTermDictionaryBuilder builder, ApplicationContext context, LawProperties properties,
                             @Value("${docai.law.terms-output:src/main/resources/law/daily-terms.json}") String output) {
        this.builder = builder;
        this.context = context;
        this.properties = properties;
        this.output = Path.of(output);
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = 0;
        try {
            if (!properties.offline() && properties.minInterval().compareTo(LawProperties.REAL_MIN_INTERVAL) < 0) {
                throw new IllegalStateException("docai.law.min-interval 은 실제 적재에서 1초 이상이어야 합니다: " + properties.minInterval());
            }
            LawTermDictionaryBuilder.BuildReport report = builder.build(properties.forceRefresh(), properties.offline());
            builder.write(report.dictionary(), output);
            report.dictionary().terms().forEach(e -> log.info("일상용어 사전: {} → {}", e.daily(), e.legal()));
            log.info("일상용어 사전 저장: {} (API 호출 {}회, 캐시 {}회)", output.toAbsolutePath().normalize(),
                    report.apiCalls(), report.cacheHits());
        } catch (RuntimeException e) {
            log.error("일상용어 사전 만들기 실패: {}", e.getMessage());
            code = 1;
        }
        if (properties.exitAfterLoad()) {
            int exit = code;
            System.exit(SpringApplication.exit(context, () -> exit));
        }
    }
}
