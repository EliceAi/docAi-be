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
    CONTRACT_NOT_ANALYZED(409, "완료된 분석이 없는 계약서입니다."),
    CONTRACT_ANALYZING(409, "분석이 진행 중입니다. 분석이 끝난 뒤 다시 시도해 주세요."),
    CONTRACT_ALREADY_ANALYZED(409, "분석이 완료된 계약서는 문서 종류를 바꿀 수 없습니다."),
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
    CONVERSATION_NOT_FOUND(404, "대화가 없거나 내 대화가 아닙니다."),
    CONVERSATION_DOCUMENT_MISMATCH(409, "이미 다른 계약서에 연결된 대화입니다. 새 대화를 시작해 주세요."),
    // 비교 담당(B) 코드. 비교 구역이 아직 없어 AI 질문에 필요한 이 코드만 여기 둔다 (B 가 Comparison 구역을 만들면 옮김)
    COMPARISON_NOT_FOUND(404, "비교가 없거나 내 비교가 아닙니다."),

    // Schedule
    SCHEDULE_NOT_FOUND(404, "일정을 찾을 수 없습니다."),
    CANDIDATE_NOT_FOUND(404, "일정 후보를 찾을 수 없습니다."),
    DATE_CONFLICT_UNRESOLVED(422, "같은 항목에 서로 다른 날짜가 둘 다 들어왔습니다. 하나만 골라 주세요."),
    VERSION_NOT_FOUND(404, "해당 버전을 찾을 수 없습니다."),
    PAGE_NOT_FOUND(404, "해당 쪽을 찾을 수 없습니다."),

    // Comparison
    COMPARISON_NOT_FOUND(404, "비교 결과를 찾을 수 없습니다."),
    COMPARISON_IMPOSSIBLE(422, "비교할 수 없는 계약서입니다."),
    PREVIOUS_NOT_ARCHIVED(409, "이전본이 보관된 계약서가 아닙니다."),
    REVISED_ALREADY_ARCHIVED(409, "수정본이 이미 보관된 계약서입니다."),
    COMPARISON_ALREADY_CLOSED(409, "이미 확정되었거나 종료된 비교입니다."),
    COMPARISON_STALE(409, "비교 이후 계약서 상태가 바뀌었습니다. 다시 비교해 주세요."),
    COMPARISON_EXPIRED(409, "수정본 계약서가 삭제되었습니다. 다시 올려 비교해 주세요."),
    UNCOMPARED_NOT_FOUND(404, "비교하지 못한 항목을 찾을 수 없습니다.");

    private final int status;
    private final String message;
}
