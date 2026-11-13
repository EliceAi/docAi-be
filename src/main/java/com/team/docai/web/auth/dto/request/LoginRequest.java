package com.team.docai.web.auth.dto.request;

import jakarta.validation.constraints.*;
import com.team.docai.domain.auth.dto.AuthCommand;
public record LoginRequest(@NotBlank(message="이메일은 필수입니다") @Email(message="이메일 형식을 확인해 주세요") String email,
    @NotBlank(message="비밀번호는 필수입니다") @Size(max=60,message="비밀번호는 60자 이내입니다") String password) {
    public AuthCommand toCommand(){return new AuthCommand(email,password,null);}
}
