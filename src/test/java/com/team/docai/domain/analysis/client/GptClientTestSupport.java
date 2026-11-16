package com.team.docai.domain.analysis.client;

import com.team.docai.global.config.OpenAiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.service.AiCallLogWriter;
import org.springframework.web.client.RestClient;

/**
 * 다른 패키지 테스트에서 재시도 대기를 건너뛰는 GptClient 를 만들 때 쓴다.
 */
public final class GptClientTestSupport {

    private GptClientTestSupport() {
    }

    public static GptClient create(RestClient restClient, OpenAiProperties properties,
                                   AiCallLogWriter writer, ObjectMapper objectMapper) {
        return new GptClient(restClient, properties, writer, objectMapper, d -> { });
    }
}
