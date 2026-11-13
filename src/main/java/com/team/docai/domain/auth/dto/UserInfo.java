package com.team.docai.domain.auth.dto;

import java.util.UUID;
import com.team.docai.domain.auth.entity.AppUser;
public record UserInfo(UUID id,String email,String name) {public static UserInfo from(AppUser u){return new UserInfo(u.getId(),u.getEmail(),u.getName());}}
