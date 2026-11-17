package com.team.docai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.contract.dto.CreateContractCommand;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.auth.TokenService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
@EnabledIfEnvironmentVariable(named="DOCAI_AUTH_INTEGRATION_TESTS",matches="true")
@Import(BServiceIntegrationTest.FixedTime.class)
class BServiceIntegrationTest {
 @TestConfiguration static class FixedTime {
  @Bean @Primary Clock fixedClock(){return Clock.fixed(Instant.parse("2026-11-17T15:01:00Z"),ZoneOffset.UTC);}
 }
 @Autowired MockMvc mvc;
 @Autowired JdbcTemplate jdbc;
 @Autowired TokenService tokens;
 @Autowired ContractService contracts;
 @Autowired ObjectMapper mapper;
 UUID user,other;
 @BeforeEach void users(){user=createUser();other=createUser();}
 UUID createUser(){
  UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(user_id,device_id_hash,created_at) VALUES (?,?,now())",id,UUID.randomUUID().toString().replace("-","").repeat(2));return id;
 }
 String auth(UUID id){return "Bearer "+tokens.issue(id);}
 long contract() throws Exception {
  var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(12,12,BufferedImage.TYPE_INT_RGB),"png",out);
  return contracts.create(user,UUID.randomUUID(),new CreateContractCommand("LEASE",List.of(out.toByteArray()),List.of(0))).contractId();
 }
 long schedule(UUID owner,Long contract,String title,LocalDate date){
  return jdbc.queryForObject("INSERT INTO schedule(user_id,contract_id,title,date,source,created_at,updated_at) VALUES (?,?,?,?,'MANUAL',now(),now()) RETURNING schedule_id",Long.class,owner,contract,title,date);
 }
 long notification(UUID owner,long schedule,boolean read){
  return jdbc.queryForObject("INSERT INTO notification(user_id,type,title,schedule_id,due_offset,due_date,is_read,created_at,updated_at) VALUES (?,'SCHEDULE_DUE','일정 알림',?,7,'2026-11-18',?,now(),now()) RETURNING notification_id",Long.class,owner,schedule,read);
 }
 @Test void catalogIncludes23AndOnlyTwoSupported() throws Exception {
  var response=mvc.perform(get("/api/document-types").header("Authorization",auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(23)).andReturn().getResponse();
  var data=mapper.readTree(response.getContentAsString()).get("data");var supported=new HashSet<String>();var codes=new HashSet<String>();
  for(var group:data.get("groups"))for(var type:group.get("documentTypes")){assertTrue(codes.add(type.get("code").asText()));if(type.get("supported").asBoolean())supported.add(type.get("code").asText());}
  assertEquals(Set.of("LEASE","EMPLOYMENT"),supported);assertEquals(23,codes.size());
  mvc.perform(get("/api/document-types").param("category","WORK").header("Authorization",auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(10));
  mvc.perform(get("/api/document-types").param("keyword","보증금").header("Authorization",auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(2));
  mvc.perform(get("/api/document-types").param("keyword","%_").header("Authorization",auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(0));
  mvc.perform(get("/api/document-types").param("category","INVALID").header("Authorization",auth(user))).andExpect(status().isBadRequest());
 }
 @Test void imageAccessChecksOwnerVersionPageAndDeletion() throws Exception {
  long id=contract();
  byte[] bytes=mvc.perform(get("/api/contracts/{id}/pages/1",id).header("Authorization",auth(user))).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg")).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
  assertEquals(0xff,bytes[0]&255);assertEquals(0xd8,bytes[1]&255);
  mvc.perform(get("/api/contracts/{id}/pages/1",id).param("version","1").header("Authorization",auth(user))).andExpect(status().isOk()).andExpect(content().bytes(bytes));
  mvc.perform(get("/api/contracts/{id}/pages/1",id).header("Authorization",auth(other))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
  mvc.perform(get("/api/contracts/{id}/pages/1",id).param("version","2").header("Authorization",auth(user))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
  mvc.perform(get("/api/contracts/{id}/pages/2",id).header("Authorization",auth(user))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAGE_NOT_FOUND"));
  jdbc.update("UPDATE contract SET deleted_at=now() WHERE contract_id=?",id);
  // Clear the first-level cache before reading the database change.
  entityManager.clear();
  mvc.perform(get("/api/contracts/{id}/pages/1",id).header("Authorization",auth(user))).andExpect(status().isNotFound());
 }
 @Autowired jakarta.persistence.EntityManager entityManager;
 @Test void upcomingUsesSeoulTodayAndStableOrderingAndOwner() throws Exception {
  LocalDate today=LocalDate.of(2026,11,18);
  schedule(user,null,"지난 일정",today.minusDays(1));long first=schedule(user,null,"오늘",today);long next=schedule(user,null,"내일",today.plusDays(1));
  schedule(user,null,"같은 날짜",today.plusDays(1));schedule(other,null,"다른 사람",today);
  for(String path:List.of("/api/schedules","/api/schedules/upcoming")){
   mvc.perform(get(path).header("Authorization",auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].scheduleId").value(first)).andExpect(jsonPath("$.data[0].dDay").value(0)).andExpect(jsonPath("$.data[1].scheduleId").value(next)).andExpect(jsonPath("$.data[1].dDay").value(1));
  }
  mvc.perform(get("/api/schedules").param("limit","0").header("Authorization",auth(user))).andExpect(status().isBadRequest());
  mvc.perform(get("/api/schedules").param("limit","11").header("Authorization",auth(user))).andExpect(status().isBadRequest());
  mvc.perform(get("/api/schedules").param("limit","abc").header("Authorization",auth(user))).andExpect(status().isBadRequest());
 }
 @Test void deletedContractDoesNotLeakItsTitleInSchedule() throws Exception {
  long id=contract();contracts.setTitleIfEmpty(id,"내 계약서");schedule(user,id,"일정",LocalDate.of(2026,11,18));
  mvc.perform(get("/api/schedules").header("Authorization",auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].contractTitle").value("내 계약서"));
  jdbc.update("UPDATE contract SET deleted_at=now() WHERE contract_id=?",id);entityManager.clear();
  mvc.perform(get("/api/schedules").header("Authorization",auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].contractId").doesNotExist()).andExpect(jsonPath("$.data[0].contractTitle").doesNotExist());
 }
 @Test void deletingScheduleCascadesOnlyItsNotificationsAndKeepsContract() throws Exception {
  long contract=contract();long first=schedule(user,contract,"삭제",LocalDate.of(2026,11,18));long keep=schedule(user,contract,"유지",LocalDate.of(2026,11,19));
  notification(user,first,false);notification(user,keep,false);
  mvc.perform(delete("/api/schedules/{id}",first).header("Authorization",auth(other))).andExpect(status().isNotFound());
  mvc.perform(delete("/api/schedules/{id}",first).header("Authorization",auth(user))).andExpect(status().isOk());entityManager.flush();
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification WHERE schedule_id=?",Integer.class,first));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notification WHERE schedule_id=?",Integer.class,keep));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM contract WHERE contract_id=?",Integer.class,contract));
  mvc.perform(delete("/api/schedules/{id}",first).header("Authorization",auth(user))).andExpect(status().isNotFound());
 }
 @Test void unreadCountIsReadOnlyAndUserScoped() throws Exception {
  notification(user,schedule(user,null,"내 일정",LocalDate.of(2026,11,18)),false);
  notification(user,schedule(user,null,"읽은 일정",LocalDate.of(2026,11,19)),true);
  notification(other,schedule(other,null,"남의 일정",LocalDate.of(2026,11,18)),false);
  for(int i=0;i<2;i++)mvc.perform(get("/api/notifications/unread-count").header("Authorization",auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(1));
 }
 @Test void allNewEndpointsRequireAuthentication() throws Exception {
  for(String path:List.of("/api/document-types","/api/contracts/1/pages/1","/api/schedules","/api/schedules/upcoming","/api/notifications/unread-count")){
   mvc.perform(get(path)).andExpect(status().isUnauthorized());
   mvc.perform(get(path).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
  }
  mvc.perform(delete("/api/schedules/1")).andExpect(status().isUnauthorized());
 }
}
