package com.team.docai.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
@Getter @RequiredArgsConstructor
public enum ErrorCode {
    // Common
    INVALID_INPUT(400,"입력값을 확인해 주세요."), UNAUTHORIZED(401,"로그인이 필요하거나 로그인 정보가 올바르지 않습니다."),
    FORBIDDEN(403,"접근 권한이 없거나 요청 인증이 만료되었습니다."), INTERNAL_SERVER_ERROR(500,"서버 오류가 발생했습니다."),
    // Auth
    EMAIL_EXISTS(409,"이미 가입된 이메일입니다."),
    // Document
    DOCUMENT_NOT_FOUND(404,"문서를 찾을 수 없습니다."), DOCUMENT_NOT_READY(409,"분석이 완료된 문서가 아닙니다."),
    DOCUMENT_UNSUPPORTED(400,"추후 업데이트 예정인 문서입니다."), DOCUMENT_CONFLICT(409,"문서 상태가 변경되었습니다. 새로고침해 주세요."),
    FILE_INVALID(400,"PNG·JPEG 이미지와 쪽 수·용량을 확인해 주세요."), FILE_STORAGE_FAILED(500,"가린 이미지를 저장하지 못했습니다. 원본은 전송하지 마세요."),
    // AI
    AI_NOT_CONFIGURED(503,"OpenAI 연결 지점만 준비되어 있습니다. 현재 외부 API 호출은 비활성화되어 있습니다."),
    AI_FAILED(502,"분석에 실패했습니다. 다시 시도해 주세요."),
    // Chat
    CHAT_NOT_FOUND(404,"대화를 찾을 수 없습니다."),
    // Schedule
    SCHEDULE_NOT_FOUND(404,"일정을 찾을 수 없습니다."),
    // Comparison
    COMPARISON_INVALID(400,"보관된 같은 종류의 이전본과 보관 전 수정본을 선택해 주세요.");
    private final int status; private final String message;
}
