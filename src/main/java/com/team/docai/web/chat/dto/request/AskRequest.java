package com.team.docai.web.chat.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.team.docai.domain.chat.dto.AskCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * AI 질문 요청. message 는 앞뒤 공백(strip)을 지운 값으로 검사하고 넘긴다.
 * <ul>
 *   <li>지운 뒤 비거나 1000자(유니코드 코드 포인트)를 넘으면 INVALID_INPUT.</li>
 *   <li>screen 은 S01~S15.</li>
 *   <li>ID 는 양수만.</li>
 * </ul>
 */
public record AskRequest(
        @Positive(message = "대화 ID 형식이 올바르지 않습니다.") Long conversationId,
        @NotNull(message = "질문은 필수입니다.") String message,
        @NotNull(message = "화면 번호는 필수입니다.")
        @Pattern(regexp = "S(0[1-9]|1[0-5])", message = "화면 번호는 S01~S15 입니다.") String screen,
        @Positive(message = "계약서 ID 형식이 올바르지 않습니다.") Long contractId,
        @Positive(message = "분석 ID 형식이 올바르지 않습니다.") Long analysisId,
        @Positive(message = "조항 ID 형식이 올바르지 않습니다.") Long clauseId,
        @Positive(message = "비교 ID 형식이 올바르지 않습니다.") Long comparisonId) {

    static final int MESSAGE_MAX = 1000;

    @JsonIgnore
    @AssertTrue(message = "질문은 필수입니다.")
    public boolean isMessagePresent() {
        return message == null || !message.strip().isEmpty();
    }

    @JsonIgnore
    @AssertTrue(message = "질문은 1000자 이하로 입력해 주세요.")
    public boolean isMessageWithinLimit() {
        if (message == null) {
            return true;
        }
        String m = message.strip();
        return m.codePointCount(0, m.length()) <= MESSAGE_MAX;
    }

    public AskCommand toCommand() {
        return new AskCommand(conversationId, message.strip(), screen, contractId, analysisId, clauseId, comparisonId);
    }
}
