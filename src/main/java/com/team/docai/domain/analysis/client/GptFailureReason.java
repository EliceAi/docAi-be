package com.team.docai.domain.analysis.client;

import com.team.docai.global.error.ErrorCode;

/**
 * GPT 호출 실패 사유. retryable 은 GptClient 가 스스로 재시도하는 사유 (429, 5xx, 시간 초과, 네트워크 오류).
 * 아키텍처 '중간에 끝나는 경우': "GPT 오류·시간 초과 → 재시도 후 FAILED · TEMPORARY_ERROR".
 * 분석 파이프라인은 어떤 사유든 분석 실패 사유 TEMPORARY_ERROR 로 이어 쓴다 (분석 실패 사유는 3가지뿐).
 * 응답 내용 오류(깨진 JSON, 스키마 불일치, 출력 잘림 LENGTH)는 GptClient 가 다시 부르지 않는다. 다시 부를지는 부르는 쪽이
 * 정한다 (담당_연결지점 A8. 예: 분석 1차 ExtractStep 은 그 묶음만 1회 다시 부름).
 */
public enum GptFailureReason {
    /** 연결·읽기 시간 초과 */
    TIMEOUT(true, ErrorCode.AI_TIMEOUT),
    /** 429 */
    RATE_LIMITED(true, ErrorCode.AI_REQUEST_FAILED),
    /** 429 + error.code = insufficient_quota 또는 credit_balance_exhausted (사용 한도·결제·크레딧 소진, 기다려도 풀리지 않음) */
    QUOTA_EXCEEDED(false, ErrorCode.AI_REQUEST_FAILED),
    /** 5xx */
    SERVER_ERROR(true, ErrorCode.AI_REQUEST_FAILED),
    /** 시간 초과가 아닌 네트워크 오류 (연결 거부·끊김 등). 일시적 오류로 보고 재시도 */
    NETWORK_ERROR(true, ErrorCode.AI_REQUEST_FAILED),
    /** 400·401·403 등 4xx (429 제외), 요청을 보내기 전 클라이언트 쪽 오류 (헤더 값 이상 등) */
    CLIENT_ERROR(false, ErrorCode.AI_REQUEST_FAILED),
    /** API 키 미설정 */
    NOT_CONFIGURED(false, ErrorCode.AI_REQUEST_FAILED),
    /** 모델이 응답을 거절함 (message.refusal, finish_reason = content_filter) */
    REFUSAL(false, ErrorCode.AI_REQUEST_FAILED),
    /** finish_reason = length (출력이 잘림) */
    LENGTH(false, ErrorCode.AI_REQUEST_FAILED),
    /** 응답 내용이 JSON 이 아님 (비었거나 깨짐) */
    INVALID_JSON(false, ErrorCode.AI_REQUEST_FAILED),
    /** JSON 이지만 응답 record 와 맞지 않음 (필드 누락·모르는 필드·타입 다름) */
    SCHEMA_MISMATCH(false, ErrorCode.AI_REQUEST_FAILED),
    /** 요청 중 또는 재시도 대기 중 스레드가 중단됨 */
    INTERRUPTED(false, ErrorCode.AI_REQUEST_FAILED);

    private final boolean retryable;
    private final ErrorCode errorCode;

    GptFailureReason(boolean retryable, ErrorCode errorCode) {
        this.retryable = retryable;
        this.errorCode = errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
