package com.team.docai.integration;

import com.fasterxml.jackson.databind.*;
import com.team.docai.global.auth.TokenService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.web.servlet.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AnonymousUserIntegrationTest.ProbeConfig.class)
@EnabledIfEnvironmentVariable(named="DOCAI_AUTH_INTEGRATION_TESTS",matches="true")
class AnonymousUserIntegrationTest {
 @org.springframework.boot.test.context.TestConfiguration
 static class ProbeConfig {
  @org.springframework.context.annotation.Bean ProbeController probeController(){return new ProbeController();}
 }
 @org.springframework.web.bind.annotation.RestController
 static class ProbeController {
  @org.springframework.web.bind.annotation.GetMapping("/api/test/user")
  String user(@org.springframework.security.core.annotation.AuthenticationPrincipal UUID userId){return userId.toString();}
 }

 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired JdbcTemplate jdbc;
 @Autowired JwtEncoder encoder;
 @Autowired JwtDecoder decoder;
 String device(){return UUID.randomUUID().toString();}
 ResultActions issue(String device,String ip) throws Exception {
  return mvc.perform(post("/api/users/anonymous").with(r->{r.setRemoteAddr(ip);return r;}).contentType("application/json").content(mapper.writeValueAsString(Map.of("deviceId",device))));
 }
 JsonNode data(ResultActions result) throws Exception {return mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");}
 @Test void newAndExistingUserReturnSameIdAndOnlyHashIsStored() throws Exception {
  String device=device();JsonNode first=data(issue(device,"10.0.0.1").andExpect(status().isCreated()).andExpect(jsonPath("$.data.isNew").value(true)));
  JsonNode second=data(issue(device,"10.0.0.1").andExpect(status().isOk()).andExpect(jsonPath("$.data.isNew").value(false)));
  assertEquals(first.get("userId"),second.get("userId"));
  assertEquals(first.get("userId").asText(),decoder.decode(first.get("accessToken").asText()).getSubject());
  String hash=jdbc.queryForObject("select device_id_hash from users where user_id=?",String.class,UUID.fromString(first.get("userId").asText()));
  assertTrue(hash.matches("[0-9a-f]{64}"));assertNotEquals(device,hash);
 }
 @Test void invalidDeviceIdsAreRejected() throws Exception {
  for(String invalid:List.of("", "x", "3f2a9c1e-8b7d-1e21-9a55-0c6e1d2f7b90"))issue(invalid,"10.0.0.2").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
 }
 @Test void eleventhRequestIsLimitedEvenWhenInputIsInvalid() throws Exception {
  for(int i=0;i<10;i++)issue("bad","10.0.0.3").andExpect(status().isBadRequest());
  issue(device(),"10.0.0.3").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  issue(device(),"10.0.0.4").andExpect(status().isCreated());
 }
 @Test void protectedApiRequiresValidBearerToken() throws Exception {
  mvc.perform(get("/api/test/user")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  mvc.perform(get("/api/test/user").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
  JsonNode result=data(issue(device(),"10.0.0.5"));
  mvc.perform(get("/api/test/user").header("Authorization","Bearer "+result.get("accessToken").asText())).andExpect(status().isOk()).andExpect(content().string(result.get("userId").asText()));
  String token=result.get("accessToken").asText();int signature=token.lastIndexOf('.')+1;
  String tampered=token.substring(0,signature)+(token.charAt(signature)=='A'?'B':'A')+token.substring(signature+1);
  mvc.perform(get("/api/test/user").header("Authorization","Bearer "+tampered)).andExpect(status().isUnauthorized());
 }
 @Test void expiredTokenRejectedAndSameDeviceCanReissue() throws Exception {
  String device=device();JsonNode first=data(issue(device,"10.0.0.6"));
  Instant old=Instant.now().minusSeconds(7200);
  String expired=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),JwtClaimsSet.builder().issuer(TokenService.ISSUER).subject(first.get("userId").asText()).issuedAt(old).expiresAt(old.plusSeconds(60)).build())).getTokenValue();
  mvc.perform(get("/api/test/user").header("Authorization","Bearer "+expired)).andExpect(status().isUnauthorized());
  issue(device,"10.0.0.6").andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(first.get("userId").asText()));
 }
 @Test void concurrentRequestsCreateOneUser() throws Exception {
  String device=device();ExecutorService pool=Executors.newFixedThreadPool(4);
  try {
   var tasks=new ArrayList<Callable<MvcResult>>();
   for(int i=0;i<4;i++){String ip="10.0.1."+i;tasks.add(()->issue(device,ip).andReturn());}
   var results=pool.invokeAll(tasks);Set<String> ids=new HashSet<>();int created=0;
   for(var future:results){var response=future.get().getResponse();assertTrue(response.getStatus()==200||response.getStatus()==201);if(response.getStatus()==201)created++;ids.add(mapper.readTree(response.getContentAsString()).path("data").path("userId").asText());}
   assertEquals(1,created);assertEquals(1,ids.size());
  } finally {pool.shutdownNow();}
 }
}
