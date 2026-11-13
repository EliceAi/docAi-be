package com.team.docai.web.auth.dto.request;

import jakarta.validation.constraints.*;
import com.team.docai.domain.auth.dto.AuthCommand;
public record RegisterRequest(@NotBlank(message="이메일은 필수입니다") @Email(message="이메일 형식을 확인해 주세요") @Size(max=254,message="이메일이 너무 깁니다") String email,
    @NotBlank(message="비밀번호는 필수입니다") @Size(min=8,max=60,message="비밀번호는 8~60자입니다") String password,
    @NotBlank(message="이름은 필수입니다") @Size(max=40,message="이름은 40자 이내입니다") String name) {
    public AuthCommand toCommand(){return new AuthCommand(email,password,name);}
}
