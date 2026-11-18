package com.team.docai.web.analysis.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.team.docai.domain.analysis.dto.ManualInputCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * 미인식 항목 입력 요청. 앞뒤 공백(strip)을 지운 값으로 검사하고 넘긴다.
 * <ul>
 *   <li>지운 뒤 비면 INVALID_INPUT (전각 공백 등 유니코드 공백만 보낸 값 포함).</li>
 *   <li>100자는 저장 칸(VARCHAR, 글자 수)과 같게 유니코드 코드 포인트로 센다 (이모지 하나 = 1자).</li>
 * </ul>
 */
public record ManualInputRequest(
        @NotNull(message = "주요 조건 이름은 필수입니다.") String label,
        @NotNull(message = "입력값은 필수입니다.") String value) {

    static final int VALUE_MAX = 100;

    @JsonIgnore
    @AssertTrue(message = "주요 조건 이름은 필수입니다.")
    public boolean isLabelPresent() {
        return label == null || !label.strip().isEmpty();
    }

    @JsonIgnore
    @AssertTrue(message = "입력값은 필수입니다.")
    public boolean isValuePresent() {
        return value == null || !value.strip().isEmpty();
    }

    @JsonIgnore
    @AssertTrue(message = "입력값은 100자 이하로 입력해 주세요.")
    public boolean isValueWithinLimit() {
        if (value == null) {
            return true;
        }
        String v = value.strip();
        return v.codePointCount(0, v.length()) <= VALUE_MAX;
    }

    public ManualInputCommand toCommand() {
        return new ManualInputCommand(label.strip(), value.strip());
    }
}
