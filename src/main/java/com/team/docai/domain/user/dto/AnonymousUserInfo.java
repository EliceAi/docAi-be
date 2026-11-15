package com.team.docai.domain.user.dto;
import java.util.UUID;
public record AnonymousUserInfo(UUID userId,String accessToken,boolean isNew) {}
