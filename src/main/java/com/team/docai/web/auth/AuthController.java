package com.team.docai.web.auth;

import com.team.docai.domain.auth.service.AuthService;
import com.team.docai.global.common.ApiResponse;
import com.team.docai.web.auth.dto.request.*;
import com.team.docai.web.auth.dto.response.UserResponse;
import jakarta.validation.Valid;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import java.util.*;
@RestController @RequestMapping("/api/auth") @RequiredArgsConstructor
public class AuthController {
    private final AuthService auth; private final HttpSessionSecurityContextRepository contexts;
    @GetMapping("/csrf") public ApiResponse<Map<String,String>> csrf(CsrfToken token){return ApiResponse.success(Map.of("token",token.getToken(),"headerName",token.getHeaderName()));}
    @PostMapping("/registrations") public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest r){return ResponseEntity.status(201).body(ApiResponse.success(201,UserResponse.from(auth.register(r.toCommand()))));}
    @PostMapping("/sessions") public ApiResponse<UserResponse> login(@Valid @RequestBody LoginRequest r,HttpServletRequest req,HttpServletResponse res) {
        var u=auth.login(r.toCommand()); req.getSession();req.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(u.id(),null,List.of()));
        SecurityContextHolder.setContext(context); contexts.saveContext(context,req,res);return ApiResponse.success(UserResponse.from(u));
    }
    @GetMapping("/me") public ApiResponse<UserResponse> me(@AuthenticationPrincipal UUID user){return ApiResponse.success(UserResponse.from(auth.me(user)));}
}
