package com.team.docai.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.global.common.ApiResponse;
import com.team.docai.global.error.ErrorCode;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean HttpSessionSecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, HttpSessionSecurityContextRepository repository) throws Exception {
        http.securityContext(c -> c.securityContextRepository(repository))
            .authorizeHttpRequests(a -> a.requestMatchers("/", "/index.html", "/app.js", "/style.css", "/vendor/**", "/favicon.ico", "/api/auth/csrf", "/api/auth/registrations", "/api/auth/sessions", "/error").permitAll().anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> {res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); mapper.writeValue(res.getWriter(), ApiResponse.error(ErrorCode.UNAUTHORIZED));})
                .accessDeniedHandler((req,res,ex) -> {res.setStatus(403); res.setContentType("application/json;charset=UTF-8"); mapper.writeValue(res.getWriter(), ApiResponse.error(ErrorCode.FORBIDDEN));}))
            .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((req,res,auth) -> {res.setContentType("application/json;charset=UTF-8"); mapper.writeValue(res.getWriter(),ApiResponse.success(null));}));
        return http.build();
    }
}
