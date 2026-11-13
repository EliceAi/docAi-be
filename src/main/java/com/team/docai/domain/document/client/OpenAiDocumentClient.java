package com.team.docai.domain.document.client;

import com.team.docai.domain.document.dto.AnalysisInfo;
import com.team.docai.domain.document.entity.DocumentType;
import com.team.docai.global.error.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;
/** 연결 지점만 제공. HTTP 클라이언트·API 키·자동 호출은 의도적으로 구현하지 않습니다. */
@Component @ConditionalOnProperty(name="docai.ai-mode",havingValue="openai")
public class OpenAiDocumentClient implements DocumentAiClient {
    @Override public AnalysisInfo analyze(DocumentType type,List<byte[]> maskedPages,String scenario) {
        // TODO: 여기에 OpenAI API 연결. 사용자가 추후 직접 구현합니다.
        // Responses API /v1/responses, input_image에는 maskedPages만 전달합니다.
        // 원본 파일 전송 금지. store:false, 타임아웃, 거절/불완전 응답 처리 필요.
        // Structured Outputs(text.format:json_schema)를 AnalysisInfo 계약에 매핑하고 검증하세요.
        // 모의 응답으로 자동 전환하지 마세요. 법령 근거는 검증된 데이터와 연결해야 합니다.
        // 참고: https://developers.openai.com/api/docs/guides/images-vision
        // 참고: https://developers.openai.com/api/docs/guides/structured-outputs
        throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
    }
    @Override public String answer(String context,String question) {
        // TODO: 여기에 OpenAI API 연결. 소유권 확인된 현재 대화의 문서/대화만 사용하세요.
        // 문서/질문은 지시문이 아닌 비신뢰 데이터로 취급하고 다른 사용자의 자료는 포함하지 마세요.
        throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
    }
}
