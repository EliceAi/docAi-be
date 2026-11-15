package com.team.docai.global.auth;
import java.time.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class TokenService {
 public static final String ISSUER="docai";
 private final JwtEncoder encoder;
 public String issue(UUID userId){
  Instant now=Instant.now();
  JwtClaimsSet claims=JwtClaimsSet.builder().issuer(ISSUER).subject(userId.toString()).issuedAt(now).expiresAt(now.plus(Duration.ofHours(24))).build();
  return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
 }
}
