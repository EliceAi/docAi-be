package com.team.docai.domain.auth.service;

import com.team.docai.domain.auth.dto.*;
import com.team.docai.domain.auth.entity.AppUser;
import com.team.docai.domain.auth.repository.AppUserRepository;
import com.team.docai.global.error.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class AuthService {
    private final AppUserRepository users; private final PasswordEncoder encoder;
    @Transactional public UserInfo register(AuthCommand c) {
        if(c.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new BusinessException(ErrorCode.INVALID_INPUT,"비밀번호는 UTF-8 기준 72바이트 이내로 입력해 주세요.");
        String email=c.email().strip().toLowerCase(Locale.ROOT);
        if(users.existsByEmail(email)) throw new BusinessException(ErrorCode.EMAIL_EXISTS);
        return UserInfo.from(users.saveAndFlush(AppUser.create(email,encoder.encode(c.password()),c.name().strip())));
    }
    public UserInfo login(AuthCommand c) {
        AppUser u=users.findByEmail(c.email().strip().toLowerCase(Locale.ROOT)).orElseThrow(()->new BusinessException(ErrorCode.UNAUTHORIZED));
        if(!encoder.matches(c.password(),u.getPasswordHash())) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return UserInfo.from(u);
    }
    public UserInfo me(UUID id){return users.findById(id).map(UserInfo::from).orElseThrow(()->new BusinessException(ErrorCode.UNAUTHORIZED));}
}
