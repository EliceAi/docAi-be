package com.team.docai.web.comparison.dto.request;

import com.team.docai.domain.comparison.dto.ComparisonInputCommand;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record ComparisonInputRequest(@NotNull(message="이전본은 필수입니다") UUID previousId,@NotNull(message="수정본은 필수입니다") UUID currentId,
    @NotBlank(message="항목은 필수입니다") @Size(max=150,message="항목은 150자 이내입니다") String label,
    @NotBlank(message="이전 값을 입력해 주세요") @Size(max=1000,message="값은 1000자 이내입니다") String before,
    @NotBlank(message="수정 값을 입력해 주세요") @Size(max=1000,message="값은 1000자 이내입니다") String after){public ComparisonInputCommand toCommand(){return new ComparisonInputCommand(previousId,currentId,label,before,after);}}
