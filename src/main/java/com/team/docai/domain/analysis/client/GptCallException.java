package com.team.docai.domain.analysis.client;

import com.team.docai.global.error.BusinessException;
import lombok.Getter;

/**
 * GPT 호출 최종 실패. 응답 메시지는 ErrorCode 의 기본 문구만 쓰고,
 * 내부 원인(detail)은 사용자 응답에 노출하지 않는다.
 */
@Getter
public class GptCallException extends BusinessException {

    private final GptFailureReason reason;
    private final String detail;
    private final int attempts;

    public GptCallException(GptFailureReason reason, String detail, int attempts) {
        super(reason.getErrorCode());
        this.reason = reason;
        this.detail = detail;
        this.attempts = attempts;
    }

    @Override
    public String toString() {
        return "GptCallException[reason=" + reason + ", attempts=" + attempts + ", detail=" + detail + "]";
    }
}
