package com.team.docai.web.user.dto.request;
import jakarta.validation.constraints.*;
import com.team.docai.domain.user.dto.AnonymousUserCommand;
public record AnonymousUserRequest(
 @NotBlank(message="기기 식별값은 필수입니다.")
 @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",message="UUID v4 형식으로 입력해 주세요.") String deviceId){
 public AnonymousUserCommand toCommand(){return new AnonymousUserCommand(deviceId);}
}
