package com.team.docai.domain.user.service;
import com.team.docai.domain.user.dto.*;
import com.team.docai.domain.user.entity.User;
import com.team.docai.domain.user.repository.UserRepository;
import com.team.docai.global.auth.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class UserService {
 private final UserRepository users;
 private final TokenService tokens;
 @Transactional
 public AnonymousUserInfo issueAnonymous(AnonymousUserCommand command){
  String hash=hashDeviceId(command.deviceId());
  User proposed=User.create(hash);
  boolean isNew=users.insertIfAbsent(proposed.getUserId(),hash,proposed.getCreatedAt())==1;
  User user=users.findByDeviceIdHash(hash).orElseThrow();
  return new AnonymousUserInfo(user.getUserId(),tokens.issue(user.getUserId()),isNew);
 }
 static String hashDeviceId(String deviceId){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(deviceId.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
}
