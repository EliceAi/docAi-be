package com.team.docai.domain.contract.service;
import com.team.docai.domain.analysis.dto.*;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.contract.dto.*;
import com.team.docai.domain.contract.entity.*;
import com.team.docai.domain.contract.repository.*;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.global.storage.FileStorage;
import com.team.docai.global.error.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
@Service @Slf4j @Transactional(readOnly=true)
public class ContractService {
 private final ContractRepository contracts;private final ContractVersionRepository versions;private final ContractPageRepository pages;
 private final DocumentTypeService types;private final FileStorage storage;private final JdbcTemplate jdbc;private final ObjectMapper mapper;
 private final ObjectProvider<ScheduleService> scheduleService;
 private final ObjectProvider<AnalysisService> analysisService;
 public ContractService(ContractRepository contracts,ContractVersionRepository versions,ContractPageRepository pages,DocumentTypeService types,FileStorage storage,JdbcTemplate jdbc,ObjectMapper mapper,ObjectProvider<ScheduleService> scheduleService){
  this(contracts,versions,pages,types,storage,jdbc,mapper,scheduleService,null);
 }
 @Autowired
 public ContractService(ContractRepository contracts,ContractVersionRepository versions,ContractPageRepository pages,DocumentTypeService types,FileStorage storage,JdbcTemplate jdbc,ObjectMapper mapper,ObjectProvider<ScheduleService> scheduleService,ObjectProvider<AnalysisService> analysisService){
  this.contracts=contracts;this.versions=versions;this.pages=pages;this.types=types;this.storage=storage;this.jdbc=jdbc;this.mapper=mapper;this.scheduleService=scheduleService;this.analysisService=analysisService;
 }
 public ContractVersionInfo getVersionOwner(UUID userId,Long versionId){
  if(userId==null||versionId==null)throw new BusinessException(ErrorCode.CONTRACT_NOT_FOUND);
  var version=versions.findById(versionId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  var contract=contracts.findByContractIdAndUserIdAndDeletedAtIsNull(version.getContractId(),userId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  var items=pages.findByVersionIdOrderByPageNoAsc(versionId);
  return new ContractVersionInfo(contract.getContractId(),version.getVersionNo(),items.size(),contract.getTitle(),items.stream().mapToInt(ContractPage::getMaskedCount).sum());
 }
 public Optional<SimilarContractInfo> getSimilarContractInfo(UUID userId,Long contractId){
  if(userId==null||contractId==null)return Optional.empty();
  return contracts.findByContractIdAndUserIdAndDeletedAtIsNull(contractId,userId).filter(Contract::isArchived)
   .map(c->{
    OffsetDateTime analyzedAt=null;
    var analysis=analysisService==null?null:analysisService.getIfAvailable();
    if(analysis!=null){
     analyzedAt=versions.findByContractIdAndVersionNo(c.getContractId(),c.getCurrentVersionNo())
      .map(ContractVersion::getLatestAnalysisId).filter(Objects::nonNull)
      .flatMap(analysis::getSummary).map(AnalysisSummaryInfo::completedAt).orElse(null);
    }
    return new SimilarContractInfo(c.getContractId(),c.getTitle(),analyzedAt,scheduleService.getObject().countForContract(userId,contractId));
   });
 }
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
 public int mergeRevisedVersion(UUID userId,Long previousContractId,Long revisedContractId,Long revisedVersionId){
  Contract prev=contracts.findByContractIdAndUserIdAndDeletedAtIsNull(previousContractId,userId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  Contract rev=contracts.findByContractIdAndUserIdAndDeletedAtIsNull(revisedContractId,userId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  ContractVersion revVer=versions.findById(revisedVersionId).orElseThrow(()->new BusinessException(ErrorCode.VERSION_NOT_FOUND));
  int nextVersionNo=prev.getCurrentVersionNo()+1;
  revVer.moveToContract(previousContractId,nextVersionNo);
  prev.bumpVersion(nextVersionNo);
  if(!prev.isArchived())prev.archive(OffsetDateTime.now());
  rev.softDelete(OffsetDateTime.now());
  versions.saveAndFlush(revVer);
  contracts.saveAndFlush(prev);
  contracts.saveAndFlush(rev);
  return nextVersionNo;
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public void softDeleteIfTemporary(UUID userId,Long contractId){
  if(contractId==null)return;
  contracts.findByContractIdAndUserIdAndDeletedAtIsNull(contractId,userId).filter(c->!c.isArchived()).ifPresent(c->{
   c.softDelete(OffsetDateTime.now());
   contracts.saveAndFlush(c);
  });
 }
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
 public List<ArchivedContractInfo> findArchivedSameType(UUID userId,String documentTypeCode,Long excludeContractId){
  if(userId==null||documentTypeCode==null)return List.of();
  return contracts.findByUserIdAndDocumentTypeCodeAndArchivedTrueAndDeletedAtIsNullOrderByContractIdDesc(userId,documentTypeCode).stream()
   .filter(c->excludeContractId==null||!excludeContractId.equals(c.getContractId()))
   .flatMap(c->versions.findByContractIdAndVersionNo(c.getContractId(),c.getCurrentVersionNo()).stream())
   .filter(v->v.getLatestAnalysisId()!=null)
   .map(v->new ArchivedContractInfo(v.getContractId(),v.getLatestAnalysisId()))
   .toList();
 }
 @Transactional
 public ArchiveContractInfo archive(UUID userId,Long contractId,UUID idempotencyKey,ArchiveContractCommand command){
  if(userId==null||contractId==null||idempotencyKey==null)throw new BusinessException(ErrorCode.INVALID_INPUT);
  var items=command==null||command.schedules()==null?List.<ArchiveContractCommand.ScheduleItem>of():command.schedules();
  String hash=archiveHash(contractId,items);
  if(jdbc!=null){
   jdbc.update("INSERT INTO idempotency_record(user_id,endpoint,idempotency_key,request_hash,status,created_at,updated_at) VALUES (?,'CONTRACT_ARCHIVE',?,?,'IN_PROGRESS',now(),now()) ON CONFLICT DO NOTHING",userId,idempotencyKey.toString(),hash);
   var record=jdbc.queryForMap("SELECT request_hash,status,response_json::text AS response FROM idempotency_record WHERE user_id=? AND endpoint='CONTRACT_ARCHIVE' AND idempotency_key=? FOR UPDATE",userId,idempotencyKey.toString());
   if(!hash.equals(record.get("request_hash")))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
   if("DONE".equals(record.get("status"))){try{return mapper.readValue((String)record.get("response"),ArchiveContractInfo.class);}catch(Exception e){throw new IllegalStateException(e);}}
  }
  contracts.findByContractIdAndUserIdAndDeletedAtIsNull(contractId,userId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  Contract contract=contracts.lockActive(contractId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  if(!userId.equals(contract.getUserId()))throw new BusinessException(ErrorCode.CONTRACT_NOT_FOUND);
  ContractVersion version=versions.findByContractIdAndVersionNo(contractId,contract.getCurrentVersionNo()).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  var analysis=analysisService==null?null:analysisService.getIfAvailable();
  if(analysis!=null&&analysis.getVersionState(version.getVersionId()).hasAnalyzing())throw new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED);
  if(version.getLatestAnalysisId()==null)throw new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED);
  if(!contract.isArchived()){
   contract.archive(OffsetDateTime.now());
   contracts.saveAndFlush(contract);
  }
  var schedules=scheduleService==null?null:scheduleService.getIfAvailable();
  List<ArchiveContractInfo.SavedSchedule> saved=schedules==null?List.of():schedules.saveForArchive(userId,contractId,version.getLatestAnalysisId(),items);
  ArchiveContractInfo info=new ArchiveContractInfo(contractId,true,contract.getCurrentVersionNo(),saved);
  if(jdbc!=null){
   try{jdbc.update("UPDATE idempotency_record SET status='DONE',response_json=CAST(? AS jsonb),updated_at=now() WHERE user_id=? AND endpoint='CONTRACT_ARCHIVE' AND idempotency_key=?",mapper.writeValueAsString(info),userId,idempotencyKey.toString());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
  }
  return info;
 }
 public MyContractsInfo listMyContracts(UUID userId,String category,int page,int size){
  if(userId==null)throw new BusinessException(ErrorCode.UNAUTHORIZED);
  if(page<0||size<1||size>100)throw new BusinessException(ErrorCode.INVALID_INPUT);
  if(category!=null&&!category.isBlank()&&!Set.of("ALL","HOME","WORK").contains(category))throw new BusinessException(ErrorCode.INVALID_INPUT);
  String filter=category==null||category.isBlank()?"ALL":category;
  List<Contract> all=contracts.findByUserIdAndArchivedTrueAndDeletedAtIsNullOrderByUpdatedAtDescContractIdDesc(userId);
  var analysis=analysisService==null?null:analysisService.getIfAvailable();
  var schedules=scheduleService==null?null:scheduleService.getIfAvailable();
  int homeCount=0;int workCount=0;
  List<MyContractsInfo.Item> matched=new ArrayList<>();
  for(Contract c:all){
   String cat=types!=null?types.getCategory(c.getDocumentTypeCode()):("EMPLOYMENT".equals(c.getDocumentTypeCode())?"WORK":"HOME");
   if("HOME".equals(cat))homeCount++;else if("WORK".equals(cat))workCount++;
   if(!"ALL".equals(filter)&&!filter.equals(cat))continue;
   var latestVer=versions.findByContractIdAndVersionNo(c.getContractId(),c.getCurrentVersionNo()).orElse(null);
   OffsetDateTime analyzedAt=c.getArchivedAt()!=null?c.getArchivedAt():c.getCreatedAt();
   long needsCheck=0;long missing=0;
   if(latestVer!=null&&latestVer.getLatestAnalysisId()!=null&&analysis!=null){
    var sum=analysis.getSummary(latestVer.getLatestAnalysisId()).orElse(null);
    if(sum!=null){
     if(sum.completedAt()!=null)analyzedAt=sum.completedAt();
     needsCheck=sum.needsCheckCount();
     missing=sum.missingCount();
    }
   }
   OffsetDateTime prevAnalyzedAt=null;
   if(c.getCurrentVersionNo()>1){
    var prevVer=versions.findByContractIdAndVersionNo(c.getContractId(),c.getCurrentVersionNo()-1).orElse(null);
    if(prevVer!=null){
     prevAnalyzedAt=prevVer.getCreatedAt();
     if(prevVer.getLatestAnalysisId()!=null&&analysis!=null){
      prevAnalyzedAt=analysis.getSummary(prevVer.getLatestAnalysisId()).map(AnalysisSummaryInfo::completedAt).orElse(prevAnalyzedAt);
     }
    }
   }
   int schedCount=schedules==null?0:schedules.countForContract(userId,c.getContractId());
   String typeName=types!=null?types.getName(c.getDocumentTypeCode()):("EMPLOYMENT".equals(c.getDocumentTypeCode())?"근로계약서":"임대차계약서");
   String title=c.getTitle()!=null&&!c.getTitle().isBlank()?c.getTitle():typeName;
   matched.add(new MyContractsInfo.Item(c.getContractId(),title,c.getDocumentTypeCode(),typeName,cat,analyzedAt,c.getCurrentVersionNo(),prevAnalyzedAt,schedCount,needsCheck,missing));
  }
  int from=Math.min(page*size,matched.size());
  int to=Math.min(from+size,matched.size());
  boolean hasNext=to<matched.size();
  Map<String,Integer> counts=new LinkedHashMap<>();
  counts.put("ALL",all.size());counts.put("HOME",homeCount);counts.put("WORK",workCount);
  return new MyContractsInfo(matched.subList(from,to),counts,page,size,hasNext);
 }
 public MyContractDetailInfo getMyContractDetail(UUID userId,Long contractId){
  if(userId==null||contractId==null)throw new BusinessException(ErrorCode.CONTRACT_NOT_FOUND);
  Contract c=contracts.findByContractIdAndUserIdAndDeletedAtIsNull(contractId,userId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  if(!c.isArchived())throw new BusinessException(ErrorCode.CONTRACT_NOT_FOUND);
  List<ContractVersion> verList=versions.findByContractIdOrderByVersionNoDesc(contractId);
  if(verList.isEmpty())throw new BusinessException(ErrorCode.CONTRACT_NOT_FOUND);
  var analysis=analysisService==null?null:analysisService.getIfAvailable();
  List<MyContractDetailInfo.VersionItem> versionItems=new ArrayList<>();
  ContractVersion latestVer=null;
  for(ContractVersion v:verList){
   boolean isLatest=v.getVersionNo()==c.getCurrentVersionNo();
   if(isLatest)latestVer=v;
   OffsetDateTime analyzedAt=v.getCreatedAt();
   if(v.getLatestAnalysisId()!=null&&analysis!=null){
    analyzedAt=analysis.getSummary(v.getLatestAnalysisId()).map(AnalysisSummaryInfo::completedAt).orElse(analyzedAt);
   }
   versionItems.add(new MyContractDetailInfo.VersionItem(v.getVersionNo(),v.getLatestAnalysisId(),analyzedAt,isLatest));
  }
  if(latestVer==null)latestVer=verList.get(0);
  long needsCheck=0;long missing=0;long totalClauses=0;
  List<MyContractDetailInfo.KeyTermItem> keyTerms=new ArrayList<>();
  Long latestAnalysisId=latestVer.getLatestAnalysisId();
  if(latestAnalysisId!=null&&analysis!=null){
   var sum=analysis.getSummary(latestAnalysisId).orElse(null);
   if(sum!=null){needsCheck=sum.needsCheckCount();missing=sum.missingCount();}
   var source=analysis.getComparisonSource(latestAnalysisId).orElse(null);
   if(source!=null){
    totalClauses=source.clauses().size();
    Map<String,String> sourcesByLabel=loadKeyTermSources(latestAnalysisId);
    for(var kt:source.keyTerms()){
     String ktSource=sourcesByLabel.getOrDefault(kt.label(),"MASKED".equals(kt.state())||"MISSING".equals(kt.state())||"UNREADABLE".equals(kt.state())?null:"WRITTEN");
     keyTerms.add(new MyContractDetailInfo.KeyTermItem(kt.label(),kt.value(),kt.state(),ktSource));
    }
   }
  }
  var schedules=scheduleService==null?null:scheduleService.getIfAvailable();
  List<MyContractDetailInfo.ScheduleItem> scheduleItems=schedules==null?List.of():
   schedules.listForContract(userId,contractId).stream().map(s->new MyContractDetailInfo.ScheduleItem(s.scheduleId(),s.title(),s.date(),s.dDay())).toList();
  String typeName=types!=null?types.getName(c.getDocumentTypeCode()):("EMPLOYMENT".equals(c.getDocumentTypeCode())?"근로계약서":"임대차계약서");
  String checkLevel=types!=null?types.getCheckLevel(c.getDocumentTypeCode()):"A";
  String title=c.getTitle()!=null&&!c.getTitle().isBlank()?c.getTitle():typeName;
  return new MyContractDetailInfo(c.getContractId(),title,c.getDocumentTypeCode(),typeName,checkLevel,versionItems,
   new MyContractDetailInfo.Summary(needsCheck,missing,totalClauses),keyTerms,scheduleItems);
 }
 @Transactional
 public ChangeDocumentTypeInfo changeDocumentType(UUID userId,Long contractId,String documentTypeCode){
  if(userId==null||contractId==null||documentTypeCode==null||documentTypeCode.isBlank())throw new BusinessException(ErrorCode.INVALID_INPUT);
  if(types!=null)types.requireSupported(documentTypeCode);
  contracts.findByContractIdAndUserIdAndDeletedAtIsNull(contractId,userId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  Contract contract=contracts.lockActive(contractId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  if(!userId.equals(contract.getUserId()))throw new BusinessException(ErrorCode.CONTRACT_NOT_FOUND);
  if(contract.isArchived())throw new BusinessException(ErrorCode.CONTRACT_ARCHIVED);
  ContractVersion version=versions.findByContractIdAndVersionNo(contractId,contract.getCurrentVersionNo()).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  var analysis=analysisService==null?null:analysisService.getIfAvailable();
  if(analysis!=null&&analysis.getVersionState(version.getVersionId()).hasAnalyzing())throw new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED);
  contract.changeDocumentType(documentTypeCode);
  version.clearLatestAnalysis();
  contracts.saveAndFlush(contract);
  versions.saveAndFlush(version);
  return new ChangeDocumentTypeInfo(contractId,documentTypeCode,"DRAFT");
 }
 @Transactional
 public ReuploadPageInfo reuploadPage(UUID userId,Long contractId,int pageNo,byte[] imageBytes,int maskedCount){
  if(userId==null||contractId==null||imageBytes==null||imageBytes.length==0||maskedCount<0)throw new BusinessException(ErrorCode.INVALID_INPUT);
  if(imageBytes.length>20L*1024*1024)throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
  contracts.findByContractIdAndUserIdAndDeletedAtIsNull(contractId,userId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  Contract contract=contracts.lockActive(contractId).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  if(!userId.equals(contract.getUserId()))throw new BusinessException(ErrorCode.CONTRACT_NOT_FOUND);
  if(contract.isArchived())throw new BusinessException(ErrorCode.CONTRACT_ARCHIVED);
  ContractVersion version=versions.findByContractIdAndVersionNo(contractId,contract.getCurrentVersionNo()).orElseThrow(()->new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  var analysis=analysisService==null?null:analysisService.getIfAvailable();
  if(analysis!=null&&analysis.getVersionState(version.getVersionId()).hasAnalyzing())throw new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED);
  ContractPage page=pages.findByVersionIdAndPageNo(version.getVersionId(),pageNo).orElseThrow(()->new BusinessException(ErrorCode.PAGE_NOT_FOUND));
  String oldKey=page.getImageKey();
  String newKey=storage.save(userId,imageBytes);
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCompletion(int status){
    String keyToDelete=status==STATUS_COMMITTED?oldKey:newKey;
    try{storage.delete(keyToDelete);}catch(Exception e){log.error("계약서 이미지 정리 실패: {}",keyToDelete,e);}
   }
  });
  page.replace(newKey,maskedCount,OffsetDateTime.now());
  version.clearLatestAnalysis();
  pages.saveAndFlush(page);
  versions.saveAndFlush(version);
  int pageCount=pages.findByVersionIdOrderByPageNoAsc(version.getVersionId()).size();
  return new ReuploadPageInfo(contractId,pageNo,pageCount,"DRAFT");
 }
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
 private Map<String,String> loadKeyTermSources(Long analysisId){
  if(jdbc==null)return Map.of();
  Map<String,String> map=new HashMap<>();
  jdbc.query("SELECT label,source FROM analysis_key_term WHERE analysis_id=?",rs->{
   map.put(rs.getString("label"),rs.getString("source"));
  },analysisId);
  return map;
 }
 private String archiveHash(Long contractId,List<ArchiveContractCommand.ScheduleItem> items){
  try{
   MessageDigest digest=MessageDigest.getInstance("SHA-256");
   digest.update(ByteBuffer.allocate(8).putLong(contractId).array());
   for(var s:items){
    String line=(s.candidateId()==null?"null":s.candidateId())+"|"+s.title()+"|"+s.date()+"|"+s.source()+"\n";
    digest.update(line.getBytes(StandardCharsets.UTF_8));
   }
   return HexFormat.of().formatHex(digest.digest());
  }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 private String requestHash(CreateContractCommand command){
  try{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] type=command.documentTypeCode().getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(type.length).array());digest.update(type);
   for(int i=0;i<command.pages().size();i++){byte[] page=command.pages().get(i);digest.update(ByteBuffer.allocate(8).putInt(command.maskedCounts().get(i)).putInt(page.length).array());digest.update(page);}return HexFormat.of().formatHex(digest.digest());
  }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
}
