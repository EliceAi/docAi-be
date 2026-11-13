package com.team.docai.web.auth.dto.response;

import com.team.docai.domain.auth.dto.UserInfo;
import java.util.UUID;
public record UserResponse(UUID id,String email,String name){public static UserResponse from(UserInfo i){return new UserResponse(i.id(),i.email(),i.name());}}
