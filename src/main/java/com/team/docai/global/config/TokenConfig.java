package com.team.docai.global.config;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.team.docai.global.auth.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
@Configuration
public class TokenConfig {
 @Bean SecretKey tokenKey(@Value("${docai.auth.token-secret:}") String configured){
  byte[] key=configured.getBytes(StandardCharsets.UTF_8);
  if(key.length==0){key=new byte[32];new SecureRandom().nextBytes(key);}
  else if(key.length<32)throw new IllegalStateException("DOCAI_TOKEN_SECRET는 32바이트 이상이어야 합니다.");
  return new SecretKeySpec(key,"HmacSHA256");
 }
 @Bean JwtEncoder jwtEncoder(SecretKey key){return new NimbusJwtEncoder(new ImmutableSecret<>(key));}
 @Bean JwtDecoder jwtDecoder(SecretKey key){
  NimbusJwtDecoder decoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenService.ISSUER));return decoder;
 }
}
