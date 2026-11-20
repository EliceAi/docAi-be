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
 * 7일 정리 배치 (담당_연결지점.md B 담당 배치).
 * 1) 7일 이상 보관되지 않은 미보관 계약서(archived=false, deleted_at IS NULL, created_at < now-7d):
 *    분석 중이 아니면 소프트 삭제, 관련 확정 전 비교 DISCARDED 처리, 저장된 이미지 파일 정리.
 * 2) 이미 소프트 삭제된 계약서(deleted_at IS NOT NULL):
 *    남아 있는 페이지 이미지 파일 정리 (DB 기록과 분석·알림 이력은 유지).
 * 3) idempotency_record 정리: 보관 기간은 명세상 미확정("일정 기간 지나면 삭제")이므로
 *    docai.batch.cleanup.idempotency-retention-days가 양수로 설정된 경우에만 실행한다.
 * 실행 시각(cron)도 명세상 미확정이므로 docai.batch.cleanup.enabled=true 및 cron 설정 시 동작한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix="docai.batch.cleanup",name="enabled",havingValue="true",matchIfMissing=false)
public class ContractCleanupBatch {
 static final int CONTRACT_RETENTION_DAYS=7;

 private final ContractRepository contracts;
 private final ContractVersionRepository versions;
 private final ContractPageRepository pages;
 private final FileStorage storage;
 private final ObjectProvider<AnalysisService> analysisProvider;
 private final ObjectProvider<ComparisonService> comparisonProvider;
 private final ObjectProvider<JdbcTemplate> jdbcProvider;
 private final Clock clock;
 private final Integer idempotencyRetentionDays;

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
  @Value("${docai.batch.cleanup.idempotency-retention-days:#{null}}") Integer idempotencyRetentionDays
 ){
  this.contracts=contracts;
  this.versions=versions;
  this.pages=pages;
  this.storage=storage;
  this.analysisProvider=analysisProvider;
  this.comparisonProvider=comparisonProvider;
  this.jdbcProvider=jdbcProvider;
  this.clock=clock;
  this.idempotencyRetentionDays=idempotencyRetentionDays;
 }

 @Scheduled(cron="${docai.batch.cleanup.cron:-}",zone="Asia/Seoul")
 @Transactional
 public int run(){
  OffsetDateTime now=OffsetDateTime.now(clock);
  OffsetDateTime cutoff=now.minusDays(CONTRACT_RETENTION_DAYS);
  int cleanedContracts=0;
  Set<String> keysToDelete=new LinkedHashSet<>();

  var analysis=analysisProvider==null?null:analysisProvider.getIfAvailable();
  var comparison=comparisonProvider==null?null:comparisonProvider.getIfAvailable();

  for(Contract c:contracts.findByArchivedFalseAndDeletedAtIsNullAndCreatedAtBefore(cutoff)){
   Optional<Contract> lockedOpt=contracts.lockActive(c.getContractId());
   if(lockedOpt.isEmpty())continue;
   Contract locked=lockedOpt.get();
   if(locked.isArchived())continue;
   List<ContractVersion> verList=versions.findByContractIdOrderByVersionNoDesc(locked.getContractId());
   boolean analyzing=analysis!=null&&verList.stream().anyMatch(v->analysis.getVersionState(v.getVersionId()).hasAnalyzing());
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
   for(ContractVersion v:versions.findByContractIdOrderByVersionNoDesc(deleted.getContractId())){
    for(ContractPage p:pages.findByVersionIdOrderByPageNoAsc(v.getVersionId())){
     if(p.getImageKey()!=null)keysToDelete.add(p.getImageKey());
    }
   }
  }

  deleteFilesAfterCommit(keysToDelete);

  var jdbc=jdbcProvider==null?null:jdbcProvider.getIfAvailable();
  int deletedIdempotency=0;
  if(jdbc!=null&&idempotencyRetentionDays!=null&&idempotencyRetentionDays>0){
   deletedIdempotency=jdbc.update("DELETE FROM idempotency_record WHERE created_at < ?",now.minusDays(idempotencyRetentionDays));
  }

  if(cleanedContracts>0||deletedIdempotency>0){
   log.info("7일 정리 배치 완료: cutoff={}, cleanedContracts={}, scheduledImages={}, deletedIdempotency={}",
    cutoff,cleanedContracts,keysToDelete.size(),deletedIdempotency);
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
