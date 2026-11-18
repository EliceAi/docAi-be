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
    CONTRACT_ARCHIVED(409, "보관된 계약서는 다시 분석할 수 없습니다. 바뀐 계약서는 새로 올려 수정본 비교로 확인해 주세요."),
    UNSUPPORTED_DOCUMENT_TYPE(400, "지원하지 않는 문서 종류입니다."),
    INVALID_FILE(400, "JPG 또는 PNG 이미지와 쪽 수를 확인해 주세요."),
    FILE_TOO_LARGE(413, "파일 용량이 제한을 초과했습니다."),
    FILE_NOT_FOUND(500, "저장된 이미지를 찾을 수 없습니다."),
    IDEMPOTENCY_KEY_REUSED(422, "같은 요청 키로 다른 내용을 보낼 수 없습니다."),


    // Analysis
    ANALYSIS_NOT_FOUND(404, "분석이 없거나 내 분석이 아닙니다."),
    CLAUSE_NOT_FOUND(404, "해당 분석에 없는 조항입니다."),
    ANALYSIS_NOT_COMPLETED(409, "분석이 끝나지 않았거나 실패한 분석입니다."),
    SAMPLE_NOT_FOUND(404, "예시가 없는 문서 종류입니다."),
    TERM_NOT_FOUND(404, "없는 항목입니다."),
    TERM_NOT_EDITABLE(409, "읽지 못한 칸이 아니라서 입력할 수 없습니다."),

    // Law

    // AI
    AI_REQUEST_FAILED(503, "AI 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요."),
    AI_TIMEOUT(504, "AI 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."),

    // Chat

    // Schedule
    SCHEDULE_NOT_FOUND(404, "일정을 찾을 수 없습니다."),
    VERSION_NOT_FOUND(404, "해당 버전을 찾을 수 없습니다."),
    PAGE_NOT_FOUND(404, "해당 쪽을 찾을 수 없습니다.");

    private final int status;
    private final String message;
}
