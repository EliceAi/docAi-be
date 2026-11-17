package com.team.docai.domain.contract.service;
import com.team.docai.domain.contract.dto.*;
import com.team.docai.domain.contract.entity.*;
import com.team.docai.domain.contract.repository.*;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.global.storage.FileStorage;
import com.team.docai.global.error.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.util.*;
import java.security.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
@Service @RequiredArgsConstructor @Slf4j @Transactional(readOnly=true)
public class ContractService {
 private final ContractRepository contracts;private final ContractVersionRepository versions;private final ContractPageRepository pages;
 private final DocumentTypeService types;private final FileStorage storage;private final JdbcTemplate jdbc;private final ObjectMapper mapper;
 public byte[] readPage(UUID userId,Long contractId,int pageNo,Integer versionNo){
  var contract=contracts.findByContractIdAndUserIdAndDeletedAtIsNull(contractId,userId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  var version=versions.findByContractIdAndVersionNo(contractId,versionNo==null?contract.getCurrentVersionNo():versionNo).orElseThrow(()->new BusinessException(ErrorCode.VERSION_NOT_FOUND));
  var page=pages.findByVersionIdAndPageNo(version.getVersionId(),pageNo).orElseThrow(()->new BusinessException(ErrorCode.PAGE_NOT_FOUND));
  return storage.read(page.getImageKey());
 }
 public ContractSummaryInfo getSummary(UUID userId,Long contractId){
  var contract=contracts.findByContractIdAndUserIdAndDeletedAtIsNull(contractId,userId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  var version=versions.findByContractIdAndVersionNo(contractId,contract.getCurrentVersionNo()).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  return new ContractSummaryInfo(contractId,contract.getTitle(),contract.isArchived(),version.getLatestAnalysisId());
 }
 public ContractForAnalysisInfo getForAnalysis(UUID userId,Long contractId){
  var contract=contracts.findByContractIdAndUserIdAndDeletedAtIsNull(contractId,userId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  var version=versions.findByContractIdAndVersionNo(contractId,contract.getCurrentVersionNo()).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  return new ContractForAnalysisInfo(contractId,version.getVersionId(),contract.getDocumentTypeCode(),contract.isArchived(),pages.findByVersionIdOrderByPageNoAsc(version.getVersionId()).stream().map(p->new ContractForAnalysisInfo.PageInfo(p.getPageNo(),p.getImageKey(),p.getReplacedAt())).toList());
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public void lockForUpdate(Long contractId){contracts.lockActive(contractId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));}
 @Transactional(propagation=Propagation.MANDATORY)
 public void linkLatestAnalysis(Long versionId,Long analysisId){
  if(analysisId==null)throw new BusinessException(ErrorCode.INVALID_INPUT);
  if(versions.link(versionId,analysisId)!=1)throw new BusinessException(ErrorCode.CONTRACT_NOT_FOUND);
 }
 @Transactional
 public void setTitleIfEmpty(Long contractId,String title){
  contracts.lockActive(contractId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  if(title==null||title.isBlank())return;
  int end=title.offsetByCodePoints(0,Math.min(100,title.codePointCount(0,title.length())));
  contracts.setTitleIfEmpty(contractId,title.substring(0,end));
 }
 public List<ArchivedContractInfo> findArchivedSameType(UUID userId,String documentTypeCode,Long excludeContractId){return List.of();}
 @Transactional
 public CreateContractInfo create(UUID userId,UUID idempotencyKey,CreateContractCommand command){
  if(command.pages()==null||command.pages().isEmpty())throw new BusinessException(ErrorCode.INVALID_INPUT);
  if(command.pages().size()>30)throw new BusinessException(ErrorCode.INVALID_FILE);
  if(command.maskedCounts()==null||command.maskedCounts().size()!=command.pages().size()||command.maskedCounts().stream().anyMatch(n->n==null||n<0))throw new BusinessException(ErrorCode.INVALID_INPUT);
  long total=command.pages().stream().mapToLong(a->a.length).sum();if(total>100L*1024*1024)throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
  String hash=requestHash(command);
  jdbc.update("INSERT INTO idempotency_record(user_id,endpoint,idempotency_key,request_hash,status,created_at,updated_at) VALUES (?,'CONTRACT_CREATE',?,?,'IN_PROGRESS',now(),now()) ON CONFLICT DO NOTHING",userId,idempotencyKey.toString(),hash);
  var record=jdbc.queryForMap("SELECT request_hash,status,response_json::text AS response FROM idempotency_record WHERE user_id=? AND endpoint='CONTRACT_CREATE' AND idempotency_key=? FOR UPDATE",userId,idempotencyKey.toString());
  if(!hash.equals(record.get("request_hash")))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
  if("DONE".equals(record.get("status"))){try{return mapper.readValue((String)record.get("response"),CreateContractInfo.class);}catch(Exception e){throw new IllegalStateException(e);}}
  types.requireSupported(command.documentTypeCode());
  List<String> saved=new ArrayList<>();
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)for(String key:saved)try{storage.delete(key);}catch(Exception e){log.error("계약서 이미지 정리 실패: {}",key,e);}}
  });
  Contract contract=contracts.saveAndFlush(Contract.create(userId,command.documentTypeCode()));
  ContractVersion version=versions.saveAndFlush(ContractVersion.create(contract.getContractId(),command.pages().size()));
  for(int i=0;i<command.pages().size();i++){
   String key=storage.save(userId,command.pages().get(i));saved.add(key);
   pages.save(ContractPage.create(version.getVersionId(),i+1,key,command.maskedCounts().get(i)));
  }
  CreateContractInfo info=new CreateContractInfo(contract.getContractId(),command.documentTypeCode(),command.pages().size(),"DRAFT");
  try{jdbc.update("UPDATE idempotency_record SET status='DONE',response_json=CAST(? AS jsonb),updated_at=now() WHERE user_id=? AND endpoint='CONTRACT_CREATE' AND idempotency_key=?",mapper.writeValueAsString(info),userId,idempotencyKey.toString());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
  return info;
 }
 private String requestHash(CreateContractCommand command){
  try{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] type=command.documentTypeCode().getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(type.length).array());digest.update(type);
   for(int i=0;i<command.pages().size();i++){byte[] page=command.pages().get(i);digest.update(ByteBuffer.allocate(8).putInt(command.maskedCounts().get(i)).putInt(page.length).array());digest.update(page);}return HexFormat.of().formatHex(digest.digest());
  }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
}
