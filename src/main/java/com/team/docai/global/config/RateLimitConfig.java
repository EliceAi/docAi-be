package com.team.docai.global.config;
import com.team.docai.global.auth.AnonymousRateLimiter;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
@Configuration @RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {
 private final AnonymousRateLimiter limiter;
 @Override public void addInterceptors(InterceptorRegistry registry){
  registry.addInterceptor(new HandlerInterceptor(){
   @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
    if("POST".equals(request.getMethod()))limiter.check(request.getRemoteAddr());return true;
   }
  }).addPathPatterns("/api/users/anonymous");
 }
}
