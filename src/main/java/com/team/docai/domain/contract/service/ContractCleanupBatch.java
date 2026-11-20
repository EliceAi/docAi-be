package com.team.docai.domain.contract.service;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.comparison.service.ComparisonService;
import com.team.docai.domain.contract.entity.*;
import com.team.docai.domain.contract.repository.*;
import com.team.docai.global.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/**
 * 7일 정리 배치 (담당_연결지점.md B 담당 배치). Asia/Seoul 기준 매일 새벽 3시 실행.
 * 1) 7일 이상 보관되지 않은 미보관 계약서(archived=false, deleted_at IS NULL, created_at < now-7d):
 *    관련 모든 버전 중 분석 중인 버전이 하나라도 있으면 제외.
 *    분석 중이 아니면 소프트 삭제(deleted_at), 관련 미확정 비교 DISCARDED 처리, 커밋 후 이미지 파일 물리 삭제.
 *    (분석 기록과 알림 기록은 유지)
 * 2) 이미 소프트 삭제된 계약서(deleted_at IS NOT NULL):
 *    분석 중이 아닌 경우 남아 있는 페이지 이미지 파일 물리 삭제 (DB 이력 유지).
 * 3) 중요 요청 기록(idempotency_record): 생성 후 24시간 경과 시 삭제.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix="docai.batch.cleanup",name="enabled",havingValue="true",matchIfMissing=true)
public class ContractCleanupBatch {
 static final int CONTRACT_RETENTION_DAYS=7;
 static final int DEFAULT_IDEMPOTENCY_RETENTION_HOURS=24;

 private final ContractRepository contracts;
 private final ContractVersionRepository versions;
 private final ContractPageRepository pages;
 private final FileStorage storage;
 private final ObjectProvider<AnalysisService> analysisProvider;
 private final ObjectProvider<ComparisonService> comparisonProvider;
 private final ObjectProvider<JdbcTemplate> jdbcProvider;
 private final Clock clock;
 private final int idempotencyRetentionHours;

 @Autowired
 public ContractCleanupBatch(
  ContractRepository contracts,
  ContractVersionRepository versions,
  ContractPageRepository pages,
  FileStorage storage,
  ObjectProvider<AnalysisService> analysisProvider,
  ObjectProvider<ComparisonService> comparisonProvider,
  ObjectProvider<JdbcTemplate> jdbcProvider,
  Clock clock,
  @Value("${docai.batch.cleanup.idempotency-retention-hours:24}") int idempotencyRetentionHours
 ){
  this.contracts=contracts;
  this.versions=versions;
  this.pages=pages;
  this.storage=storage;
  this.analysisProvider=analysisProvider;
  this.comparisonProvider=comparisonProvider;
  this.jdbcProvider=jdbcProvider;
  this.clock=clock;
  this.idempotencyRetentionHours=idempotencyRetentionHours>0?idempotencyRetentionHours:DEFAULT_IDEMPOTENCY_RETENTION_HOURS;
 }

 @Scheduled(cron="${docai.batch.cleanup.cron:0 0 3 * * *}",zone="Asia/Seoul")
 @Transactional
 public int run(){
  OffsetDateTime now=OffsetDateTime.now(clock);
  OffsetDateTime contractCutoff=now.minusDays(CONTRACT_RETENTION_DAYS);
  OffsetDateTime idempotencyCutoff=now.minusHours(idempotencyRetentionHours);
  int cleanedContracts=0;
  Set<String> keysToDelete=new LinkedHashSet<>();

  var analysis=analysisProvider==null?null:analysisProvider.getIfAvailable();
  var comparison=comparisonProvider==null?null:comparisonProvider.getIfAvailable();

  if(analysis==null){
   log.warn("7일 정리 배치: AnalysisService를 찾을 수 없어 계약서 정리를 건너뜁니다.");
  }else{
   for(Contract c:contracts.findByArchivedFalseAndDeletedAtIsNullAndCreatedAtBefore(contractCutoff)){
    Optional<Contract> lockedOpt=contracts.lockActive(c.getContractId());
    if(lockedOpt.isEmpty())continue;
    Contract locked=lockedOpt.get();
    if(locked.isArchived())continue;
    List<ContractVersion> verList=versions.findByContractIdOrderByVersionNoDesc(locked.getContractId());
    boolean analyzing=verList.stream().anyMatch(v->analysis.getVersionState(v.getVersionId()).hasAnalyzing());
    if(analyzing)continue;
    locked.softDelete(now);
    contracts.save(locked);
    if(comparison!=null)comparison.discardForRevisedContract(locked.getContractId());
    for(ContractVersion v:verList){
     for(ContractPage p:pages.findByVersionIdOrderByPageNoAsc(v.getVersionId())){
      if(p.getImageKey()!=null)keysToDelete.add(p.getImageKey());
     }
    }
    cleanedContracts++;
   }
   contracts.flush();

   for(Contract deleted:contracts.findByDeletedAtIsNotNull()){
    List<ContractVersion> verList=versions.findByContractIdOrderByVersionNoDesc(deleted.getContractId());
    boolean analyzing=verList.stream().anyMatch(v->analysis.getVersionState(v.getVersionId()).hasAnalyzing());
    if(analyzing)continue;
    for(ContractVersion v:verList){
     for(ContractPage p:pages.findByVersionIdOrderByPageNoAsc(v.getVersionId())){
      if(p.getImageKey()!=null)keysToDelete.add(p.getImageKey());
     }
    }
   }
  }

  deleteFilesAfterCommit(keysToDelete);

  var jdbc=jdbcProvider==null?null:jdbcProvider.getIfAvailable();
  int deletedIdempotency=0;
  if(jdbc!=null){
   deletedIdempotency=jdbc.update("DELETE FROM idempotency_record WHERE created_at < ?",idempotencyCutoff);
  }

  if(cleanedContracts>0||deletedIdempotency>0){
   log.info("7일 정리 배치 완료: contractCutoff={}, idempotencyCutoff={}, cleanedContracts={}, scheduledImages={}, deletedIdempotency={}",
    contractCutoff,idempotencyCutoff,cleanedContracts,keysToDelete.size(),deletedIdempotency);
  }
  return cleanedContracts;
 }

 private void deleteFilesAfterCommit(Collection<String> keysToDelete){
  if(keysToDelete.isEmpty())return;
  List<String> snapshot=List.copyOf(keysToDelete);
  Runnable deleteTask=()->{
   for(String key:snapshot){
    try{
     storage.delete(key);
    }catch(RuntimeException e){
     log.warn("7일 정리 배치 이미지 삭제 실패: key={}, error={}",key,e.toString());
    }
   }
  };
  if(TransactionSynchronizationManager.isSynchronizationActive()){
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
    @Override public void afterCommit(){deleteTask.run();}
   });
  }else{
   deleteTask.run();
  }
 }
}
