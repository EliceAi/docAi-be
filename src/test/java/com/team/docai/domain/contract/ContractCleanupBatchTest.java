package com.team.docai.domain.contract;
import com.team.docai.domain.analysis.dto.VersionAnalysisStateInfo;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.comparison.service.ComparisonService;
import com.team.docai.domain.contract.entity.*;
import com.team.docai.domain.contract.repository.*;
import com.team.docai.domain.contract.service.ContractCleanupBatch;
import com.team.docai.global.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractCleanupBatchTest {
 final ContractRepository contracts=mock(ContractRepository.class);
 final ContractVersionRepository versions=mock(ContractVersionRepository.class);
 final ContractPageRepository pages=mock(ContractPageRepository.class);
 final FileStorage storage=mock(FileStorage.class);
 final AnalysisService analysis=mock(AnalysisService.class);
 final ComparisonService comparison=mock(ComparisonService.class);
 final JdbcTemplate jdbc=mock(JdbcTemplate.class);
 final ObjectProvider<AnalysisService> analysisProvider=mock(ObjectProvider.class);
 final ObjectProvider<ComparisonService> comparisonProvider=mock(ObjectProvider.class);
 final ObjectProvider<JdbcTemplate> jdbcProvider=mock(ObjectProvider.class);
 final Instant nowInstant=Instant.parse("2026-11-20T19:00:00Z");
 final Clock clock=Clock.fixed(nowInstant,ZoneOffset.UTC);
 final ContractCleanupBatch batch=new ContractCleanupBatch(contracts,versions,pages,storage,analysisProvider,comparisonProvider,jdbcProvider,clock);

 Contract makeContract(long id,boolean archived,OffsetDateTime deletedAt) throws Exception {
  var c=Contract.class.getDeclaredConstructor();c.setAccessible(true);var entity=c.newInstance();
  ReflectionTestUtils.setField(entity,"contractId",id);
  ReflectionTestUtils.setField(entity,"archived",archived);
  ReflectionTestUtils.setField(entity,"deletedAt",deletedAt);
  return entity;
 }
 ContractVersion makeVersion(long versionId,long contractId) throws Exception {
  var c=ContractVersion.class.getDeclaredConstructor();c.setAccessible(true);var v=c.newInstance();
  ReflectionTestUtils.setField(v,"versionId",versionId);
  ReflectionTestUtils.setField(v,"contractId",contractId);
  return v;
 }
 ContractPage makePage(long versionId,String imageKey) throws Exception {
  var c=ContractPage.class.getDeclaredConstructor();c.setAccessible(true);var p=c.newInstance();
  ReflectionTestUtils.setField(p,"versionId",versionId);
  ReflectionTestUtils.setField(p,"imageKey",imageKey);
  return p;
 }

 @Test void cleansUnarchivedAndSoftDeletedImagesAndIdempotencyAndSkipsAnalyzing() throws Exception {
  when(analysisProvider.getIfAvailable()).thenReturn(analysis);
  when(comparisonProvider.getIfAvailable()).thenReturn(comparison);
  when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);

  OffsetDateTime cutoff=OffsetDateTime.ofInstant(nowInstant,ZoneOffset.UTC).minusDays(7);
  Contract expired=makeContract(1L,false,null);
  Contract analyzing=makeContract(2L,false,null);
  Contract alreadyDeleted=makeContract(3L,false,cutoff.minusDays(1));

  when(contracts.findByArchivedFalseAndDeletedAtIsNullAndCreatedAtBefore(cutoff)).thenReturn(List.of(expired,analyzing));
  when(contracts.lockActive(1L)).thenReturn(Optional.of(expired));
  when(contracts.lockActive(2L)).thenReturn(Optional.of(analyzing));
  when(contracts.findByDeletedAtIsNotNullAndDeletedAtBefore(cutoff)).thenReturn(List.of(alreadyDeleted));

  when(versions.findByContractIdOrderByVersionNoDesc(1L)).thenReturn(List.of(makeVersion(10L,1L)));
  when(versions.findByContractIdOrderByVersionNoDesc(2L)).thenReturn(List.of(makeVersion(20L,2L)));
  when(versions.findByContractIdOrderByVersionNoDesc(3L)).thenReturn(List.of(makeVersion(30L,3L)));

  when(analysis.getVersionState(10L)).thenReturn(new VersionAnalysisStateInfo(false,new VersionAnalysisStateInfo.LatestAnalysis(100L,"COMPLETED","LEASE",cutoff)));
  when(analysis.getVersionState(20L)).thenReturn(new VersionAnalysisStateInfo(true,new VersionAnalysisStateInfo.LatestAnalysis(101L,"ANALYZING","LEASE",cutoff)));

  when(pages.findByVersionIdOrderByPageNoAsc(10L)).thenReturn(List.of(makePage(10L,"img-10.png")));
  when(pages.findByVersionIdOrderByPageNoAsc(30L)).thenReturn(List.of(makePage(30L,"img-30.png")));
  when(jdbc.update("DELETE FROM idempotency_record WHERE created_at < ?",cutoff)).thenReturn(5);

  int cleaned=batch.run();
  assertEquals(1,cleaned);
  assertNotNull(expired.getDeletedAt());
  assertNull(analyzing.getDeletedAt());
  verify(contracts).save(expired);
  verify(contracts,never()).save(analyzing);
  verify(comparison).supersedeForRevisedContract(1L);
  verify(comparison,never()).supersedeForRevisedContract(2L);
  verify(storage).delete("img-10.png");
  verify(storage).delete("img-30.png");
  verify(jdbc).update("DELETE FROM idempotency_record WHERE created_at < ?",cutoff);
 }
}
