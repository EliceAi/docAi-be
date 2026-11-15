package com.team.docai.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT(400, "잘못된 입력입니다."),
    UNAUTHORIZED(401, "인증이 필요합니다."),
    FORBIDDEN(403, "권한이 없습니다."),
    INTERNAL_SERVER_ERROR(500, "서버 오류가 발생했습니다."),

    // User
    RATE_LIMITED(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),

    // Document
    CONTRACT_NOT_FOUND(404, "계약서를 찾을 수 없습니다."),
    UNSUPPORTED_DOCUMENT_TYPE(400, "지원하지 않는 문서 종류입니다."),
    INVALID_FILE(400, "JPG 또는 PNG 이미지와 쪽 수를 확인해 주세요."),
    FILE_TOO_LARGE(413, "파일 용량이 제한을 초과했습니다."),
    FILE_NOT_FOUND(500, "저장된 이미지를 찾을 수 없습니다."),
    IDEMPOTENCY_KEY_REUSED(422, "같은 요청 키로 다른 내용을 보낼 수 없습니다."),

    // Law

    // AI

    // Chat

    // Schedule
    ;

    private final int status;
    private final String message;
}
