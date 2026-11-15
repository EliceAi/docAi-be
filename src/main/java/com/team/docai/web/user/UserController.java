package com.team.docai.web.user;
import com.team.docai.domain.user.service.UserService;
import com.team.docai.global.common.ApiResponse;
import com.team.docai.web.user.dto.request.AnonymousUserRequest;
import com.team.docai.web.user.dto.response.AnonymousUserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/users") @RequiredArgsConstructor
public class UserController {
 private final UserService users;
 @PostMapping("/anonymous")
 public ResponseEntity<ApiResponse<AnonymousUserResponse>> anonymous(@Valid @RequestBody AnonymousUserRequest request){
  var info=users.issueAnonymous(request.toCommand());int status=info.isNew()?201:200;
  return ResponseEntity.status(status).body(ApiResponse.success(status,AnonymousUserResponse.from(info)));
 }
}
