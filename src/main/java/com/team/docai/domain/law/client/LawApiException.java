package com.team.docai.domain.law.client;

/** 법제처 API 호출 실패 (재시도 후에도). 메시지에는 인증값을 넣지 않는다 (가린 뒤 만든다). */
public class LawApiException extends RuntimeException {

    public LawApiException(String message) {
        super(message);
    }
}
