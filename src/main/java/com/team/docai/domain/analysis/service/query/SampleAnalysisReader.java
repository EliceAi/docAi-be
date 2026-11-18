package com.team.docai.domain.analysis.service.query;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.dto.SampleAnalysisInfo;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 예시 분석 고정 데이터. 테이블 없이 서버 리소스 {@code analysis-samples/{문서 종류 코드}.json} 을 읽는다 (ERD 설계 결정 "예시 분석").
 * <ul>
 *   <li>코드는 대문자·밑줄만 받는다. 경로 조작("../")을 막기 위해서다.</li>
 *   <li>모르는 필드가 있으면 실패한다 (파일 오타가 응답에서 조용히 빠지지 않게).</li>
 *   <li>시각은 파일에 적힌 오프셋(+09:00) 그대로 둔다.</li>
 *   <li>읽은 파일만 메모리에 둔다. 없는 코드는 저장하지 않는다 (아무 코드로나 불러 메모리를 채우지 못하게).</li>
 * </ul>
 */
@Component
public class SampleAnalysisReader {

    static final String LOCATION = "analysis-samples/";
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z_]{0,39}$");

    private final ObjectMapper mapper;
    private final Map<String, SampleAnalysisInfo> cache = new ConcurrentHashMap<>();

    public SampleAnalysisReader(ObjectMapper objectMapper) {
        this.mapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }

    public Optional<SampleAnalysisInfo> find(String documentTypeCode) {
        if (documentTypeCode == null || !CODE.matcher(documentTypeCode).matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.computeIfAbsent(documentTypeCode, this::load));
    }

    private SampleAnalysisInfo load(String code) {
        ClassPathResource resource = new ClassPathResource(LOCATION + code + ".json");
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            SampleAnalysisInfo sample = mapper.readValue(in, SampleAnalysisInfo.class);
            if (sample.result() == null || !code.equals(sample.result().documentTypeCode())) {
                throw new IllegalStateException("예시 분석 파일의 문서 종류가 파일 이름과 다릅니다: " + code);
            }
            return sample;
        } catch (IOException e) {
            throw new IllegalStateException("예시 분석 파일을 읽지 못했습니다: " + code, e);
        }
    }
}
