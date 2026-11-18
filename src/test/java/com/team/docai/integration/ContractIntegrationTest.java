package com.team.docai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.contract.dto.*;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.global.auth.TokenService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.storage.FileStorage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="DOCAI_AUTH_INTEGRATION_TESTS",matches="true")
class ContractIntegrationTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired TokenService tokens;
 @Autowired ContractService service; @Autowired NotificationService notifications; @Autowired FileStorage storage;
 @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
 UUID user;
 @BeforeEach void user(){user=UUID.randomUUID();jdbc.update("INSERT INTO users(user_id,device_id_hash,created_at) VALUES (?,?,now())",user,UUID.randomUUID().toString().replace("-","").repeat(2));}
 byte[] image() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(12,12,BufferedImage.TYPE_INT_ARGB),"png",out);return out.toByteArray();}
 CreateContractCommand command() throws Exception {return new CreateContractCommand("EMPLOYMENT",List.of(image()),List.of(0));}
 ResultActions request(UUID user,UUID key,String type,String counts,byte[] bytes) throws Exception {
  var req=multipart("/api/contracts").file(new MockMultipartFile("pages","page.png","image/png",bytes)).param("documentTypeCode",type).header("Authorization","Bearer "+tokens.issue(user));
  if(key!=null)req.header("Idempotency-Key",key.toString());if(counts!=null)req.param("maskedCounts",counts);return mvc.perform(req);
 }
 @Test void registrationStoresJpegAndAnalysisInfo() throws Exception {
  var response=request(user,UUID.randomUUID(),"EMPLOYMENT","[2]",image()).andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("DRAFT")).andReturn().getResponse();
  long id=mapper.readTree(response.getContentAsString()).path("data").path("contractId").asLong();var info=service.getForAnalysis(user,id);
  assertFalse(info.archived());assertEquals(1,info.pages().size());assertEquals(1,info.pages().get(0).pageNo());assertNull(info.pages().get(0).replacedAt());
  assertTrue(info.pages().get(0).imageKey().startsWith("contracts/"+user+"/"));byte[] jpg=storage.read(info.pages().get(0).imageKey());assertEquals(0xff,jpg[0]&255);assertEquals(0xd8,jpg[1]&255);
  assertEquals(2,jdbc.queryForObject("SELECT masked_count FROM contract_page WHERE version_id=?",Integer.class,info.versionId()));
 }
 @Test void retryReturnsOriginalAndChangedPayloadIsRejected() throws Exception {
  UUID key=UUID.randomUUID();String first=request(user,key,"EMPLOYMENT",null,image()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
  String second=request(user,key,"EMPLOYMENT","[0]",image()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();assertEquals(first,second);
  request(user,key,"EMPLOYMENT","[1]",image()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM contract WHERE user_id=?",Integer.class,user));
 }
 @Test void invalidInputsAndMissingAuthentication() throws Exception {
  request(user,null,"EMPLOYMENT",null,image()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  request(user,UUID.randomUUID(),"OTHER",null,image()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"));
  request(user,UUID.randomUUID(),"EMPLOYMENT","[]",image()).andExpect(status().isBadRequest());
  request(user,UUID.randomUUID(),"EMPLOYMENT","[1.2]",image()).andExpect(status().isBadRequest());
  request(user,UUID.randomUUID(),"EMPLOYMENT",null,new byte[]{1,2}).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILE"));
  mvc.perform(multipart("/api/contracts")).andExpect(status().isUnauthorized());
 }
 @Test void ownershipSoftDeletionAndLatestVersion() throws Exception {
  long id=service.create(user,UUID.randomUUID(),command()).contractId();
  assertEquals("CONTRACT_NOT_FOUND",assertThrows(BusinessException.class,()->service.getForAnalysis(UUID.randomUUID(),id)).getErrorCode().name());
  Long v=jdbc.queryForObject("INSERT INTO contract_version(contract_id,version_no,page_count,created_at,updated_at) VALUES (?,2,2,now(),now()) RETURNING version_id",Long.class,id);
  jdbc.update("UPDATE contract SET current_version_no=2 WHERE contract_id=?",id);
  for(int no:List.of(2,1))jdbc.update("INSERT INTO contract_page(version_id,page_no,image_key,masked_count) VALUES (?,?,?,0)",v,no,"contracts/"+user+"/"+UUID.randomUUID()+".jpg");
  var info=service.getForAnalysis(user,id);assertEquals(v,info.versionId());assertEquals(List.of(1,2),info.pages().stream().map(ContractForAnalysisInfo.PageInfo::pageNo).toList());
  jdbc.update("UPDATE contract SET deleted_at=now() WHERE contract_id=?",id);
  assertThrows(BusinessException.class,()->service.getForAnalysis(user,id));
 }
 @Test void titleOnlySetWhenEmptyAndUnicodeLimit() throws Exception {
  long id=service.create(user,UUID.randomUUID(),command()).contractId();String longTitle="😀".repeat(110);service.setTitleIfEmpty(id,longTitle);service.setTitleIfEmpty(id,"overwrite");
  assertEquals("😀".repeat(100),jdbc.queryForObject("SELECT title FROM contract WHERE contract_id=?",String.class,id));
 }
 @Test void concurrentRegistrationUsesOneContract() throws Exception {
  UUID key=UUID.randomUUID();var command=command();var pool=Executors.newFixedThreadPool(4);
  try{var results=pool.invokeAll(List.<Callable<Long>>of(()->service.create(user,key,command).contractId(),()->service.create(user,key,command).contractId(),()->service.create(user,key,command).contractId(),()->service.create(user,key,command).contractId()));Set<Long> ids=new HashSet<>();for(var f:results)ids.add(f.get());assertEquals(1,ids.size());}finally{pool.shutdownNow();}
 }
 @Test void rollbackRemovesFilesAndDatabaseRows() throws Exception {
  var command=command();String[] key={null};
  assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(s->{long id=service.create(user,UUID.randomUUID(),command).contractId();key[0]=service.getForAnalysis(user,id).pages().get(0).imageKey();throw new IllegalStateException("rollback");}));
  assertNotNull(key[0]);assertThrows(BusinessException.class,()->storage.read(key[0]));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM contract WHERE user_id=?",Integer.class,user));
 }
 @Test void foreignVersionAnalysisRejectedAtCommit() throws Exception {
  long id=service.create(user,UUID.randomUUID(),command()).contractId();long other=service.create(user,UUID.randomUUID(),command()).contractId();long v=service.getForAnalysis(user,id).versionId();long ov=service.getForAnalysis(user,other).versionId();
  Long a=jdbc.queryForObject("INSERT INTO analysis(version_id,document_type_code,status,progress_step,prompt_version,started_at,completed_at) VALUES (?,'EMPLOYMENT','COMPLETED','DONE','test',now(),now()) RETURNING analysis_id",Long.class,ov);
  assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).execute(s->{service.lockForUpdate(id);service.linkLatestAnalysis(v,a);return null;}));
  new TransactionTemplate(manager).execute(s->{service.lockForUpdate(other);service.linkLatestAnalysis(ov,a);return null;});
  assertEquals(a,jdbc.queryForObject("SELECT latest_analysis_id FROM contract_version WHERE version_id=?",Long.class,ov));
 }
 @Test void lockRequiresTransactionAndBlocksOtherWriter() throws Exception {
  long id=service.create(user,UUID.randomUUID(),command()).contractId();assertThrows(IllegalTransactionStateException.class,()->service.lockForUpdate(id));
  var pool=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);
  try{new TransactionTemplate(manager).execute(s->{service.lockForUpdate(id);var future=pool.submit(()->new TransactionTemplate(manager).execute(s2->{jdbc.execute("SET LOCAL lock_timeout='200ms'");entered.countDown();service.lockForUpdate(id);return null;}));try{assertTrue(entered.await(2,TimeUnit.SECONDS));assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;});}finally{pool.shutdownNow();}
 }
 @Test void storageRejectsPathTraversalAndAnalysisNotificationIsIdempotent() throws Exception {
  assertThrows(BusinessException.class,()->storage.read("../.env"));assertEquals(List.of(),service.findArchivedSameType(user,"EMPLOYMENT",1L));
  long id=service.create(user,UUID.randomUUID(),command()).contractId();long version=service.getForAnalysis(user,id).versionId();
  Long analysis=jdbc.queryForObject("INSERT INTO analysis(version_id,document_type_code,status,progress_step,prompt_version,started_at,completed_at) VALUES (?,'EMPLOYMENT','COMPLETED','DONE','test',now(),now()) RETURNING analysis_id",Long.class,version);
  for(int i=0;i<2;i++)notifications.createAnalysisResult(user,analysis,"ANALYSIS_DONE","title","subtitle");
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notification WHERE analysis_id=? AND type='ANALYSIS_DONE'",Integer.class,analysis));
 }

 @Test void concurrentAnalysisNotificationCreatesOneRowAndRollbackRemovesIt() throws Exception {
  long id=service.create(user,UUID.randomUUID(),command()).contractId();long version=service.getForAnalysis(user,id).versionId();
  Long analysis=jdbc.queryForObject("INSERT INTO analysis(version_id,document_type_code,status,progress_step,prompt_version,started_at,completed_at) VALUES (?,'EMPLOYMENT','COMPLETED','DONE','test',now(),now()) RETURNING analysis_id",Long.class,version);
  var pool=Executors.newFixedThreadPool(3);
  try{var tasks=java.util.stream.IntStream.range(0,3).mapToObj(n->(Callable<Void>)()->{notifications.createAnalysisResult(user,analysis,"ANALYSIS_DONE","완료",null);return null;}).toList();
   for(var f:pool.invokeAll(tasks,10,TimeUnit.SECONDS))f.get();
  }finally{pool.shutdownNow();}
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notification WHERE analysis_id=?",Integer.class,analysis));
  assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(tx->{notifications.createAnalysisResult(user,analysis,"ANALYSIS_FAILED","실패",null);throw new IllegalStateException("rollback");}));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification WHERE analysis_id=? AND type='ANALYSIS_FAILED'",Integer.class,analysis));
 }

 @Test void missingRequiredPagesReturnsInvalidInput() throws Exception {
  mvc.perform(multipart("/api/contracts").param("documentTypeCode","EMPLOYMENT").header("Idempotency-Key",UUID.randomUUID().toString()).header("Authorization","Bearer "+tokens.issue(user))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
 }
 @Test void malformedCountsCannotIgnoreTrailingJson() throws Exception {
  request(user,UUID.randomUUID(),"EMPLOYMENT","[0] true",image()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
 }
 @Test void wrongRequestContentTypeDoesNotBecomeServerError() throws Exception {
  mvc.perform(post("/api/contracts").contentType("application/json").content("{}").header("Idempotency-Key",UUID.randomUUID().toString()).header("Authorization","Bearer "+tokens.issue(user))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
 }
 @Test void thirtyPagesAllowedButThirtyOneRejected() throws Exception {
  byte[] png=image();var files=java.util.Collections.nCopies(30,png);var counts=java.util.Collections.nCopies(30,0);
  var created=service.create(user,UUID.randomUUID(),new CreateContractCommand("EMPLOYMENT",files,counts));assertEquals(30,service.getForAnalysis(user,created.contractId()).pages().size());
  var request=multipart("/api/contracts");request.param("documentTypeCode","EMPLOYMENT").header("Idempotency-Key",UUID.randomUUID().toString()).header("Authorization","Bearer "+tokens.issue(user));
  for(int n=0;n<31;n++)request.file(new MockMultipartFile("pages","page.png","image/png",png));
  mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILE"));
 }
 @Test void failedSecondPageRollsBackAndSameKeyCanRetry() throws Exception {
  UUID key=UUID.randomUUID();byte[] png=image();
  var bad=new CreateContractCommand("EMPLOYMENT",List.of(png,new byte[]{1,2}),List.of(0,0));
  assertThrows(BusinessException.class,()->service.create(user,key,bad));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM contract WHERE user_id=?",Integer.class,user));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE user_id=?",Integer.class,user));
  var saved=service.create(user,key,command());assertEquals("DRAFT",saved.status());
 }
 @Test void sameKeyDoesNotShareResponseAcrossUsers() throws Exception {
  UUID second=UUID.randomUUID();jdbc.update("INSERT INTO users(user_id,device_id_hash,created_at) VALUES (?,?,now())",second,UUID.randomUUID().toString().replace("-","").repeat(2));UUID key=UUID.randomUUID();var cmd=command();
  long firstId=service.create(user,key,cmd).contractId();long secondId=service.create(second,key,cmd).contractId();assertNotEquals(firstId,secondId);
  assertThrows(BusinessException.class,()->service.getForAnalysis(user,secondId));
 }

 @Test void excessiveFileAndTotalSizesReturn413() throws Exception {
  var single=multipart("/api/contracts").file(new MockMultipartFile("pages","large.png","image/png",new byte[0]){@Override public long getSize(){return 20L*1024*1024+1;}}).param("documentTypeCode","EMPLOYMENT").header("Idempotency-Key",UUID.randomUUID().toString()).header("Authorization","Bearer "+tokens.issue(user));
  mvc.perform(single).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
  var many=multipart("/api/contracts");many.param("documentTypeCode","EMPLOYMENT").header("Idempotency-Key",UUID.randomUUID().toString()).header("Authorization","Bearer "+tokens.issue(user));
  for(int n=0;n<6;n++)many.file(new MockMultipartFile("pages","large.png","image/png",new byte[0]){@Override public long getSize(){return 20L*1024*1024;}});
  mvc.perform(many).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
 }
 @Test void jpegInputAndBlankTitleAndMissingFile() throws Exception {
  byte[] jpg=storage.toJpeg(image());request(user,UUID.randomUUID(),"LEASE",null,jpg).andExpect(status().isCreated());
  long id=service.create(user,UUID.randomUUID(),command()).contractId();jdbc.update("UPDATE contract SET title='' WHERE contract_id=?",id);service.setTitleIfEmpty(id,"계약서 이름");assertEquals("계약서 이름",jdbc.queryForObject("SELECT title FROM contract WHERE contract_id=?",String.class,id));
  assertEquals("FILE_NOT_FOUND",assertThrows(BusinessException.class,()->storage.read("contracts/"+user+"/"+UUID.randomUUID()+".jpg")).getErrorCode().name());
  assertThrows(IllegalTransactionStateException.class,()->service.linkLatestAnalysis(1L,1L));
 }
}
