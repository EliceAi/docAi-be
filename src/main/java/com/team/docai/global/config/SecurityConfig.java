package com.team.docai.global.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.global.common.ApiResponse;
import com.team.docai.global.error.ErrorCode;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class SecurityConfig {
 @Bean SecurityFilterChain security(HttpSecurity http,ObjectMapper mapper) throws Exception {
  var unauthorized=(org.springframework.security.web.AuthenticationEntryPoint)(req,res,e)->{
   res.setStatus(401);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getOutputStream(),ApiResponse.error(ErrorCode.UNAUTHORIZED));
  };
  return http.csrf(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(c->c.requestMatchers(HttpMethod.POST,"/api/users/anonymous").permitAll().requestMatchers("/api/**").authenticated().anyRequest().permitAll())
   .exceptionHandling(c->c.authenticationEntryPoint(unauthorized))
   .oauth2ResourceServer(c->c.jwt(j->j.jwtAuthenticationConverter(jwt->new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(java.util.UUID.fromString(jwt.getSubject()), jwt, java.util.List.of()))).authenticationEntryPoint(unauthorized))
   .build();
 }
}
