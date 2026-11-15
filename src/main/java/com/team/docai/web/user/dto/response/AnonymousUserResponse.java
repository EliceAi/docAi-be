package com.team.docai.web.user.dto.response;
import java.util.UUID;
import com.team.docai.domain.user.dto.AnonymousUserInfo;
public record AnonymousUserResponse(UUID userId,String accessToken,boolean isNew){
 public static AnonymousUserResponse from(AnonymousUserInfo info){return new AnonymousUserResponse(info.userId(),info.accessToken(),info.isNew());}
}
