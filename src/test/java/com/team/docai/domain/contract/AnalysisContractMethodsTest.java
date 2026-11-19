package com.team.docai.domain.contract;
import com.team.docai.domain.contract.entity.*;
import com.team.docai.domain.contract.repository.*;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.domain.documenttype.repository.DocumentTypeRepository;
import com.team.docai.domain.documenttype.entity.DocumentType;
import com.team.docai.global.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class AnalysisContractMethodsTest {
 final UUID user=UUID.randomUUID();
 final ContractRepository contracts=mock(ContractRepository.class);
 final ContractVersionRepository versions=mock(ContractVersionRepository.class);
 final ContractPageRepository pages=mock(ContractPageRepository.class);
 final ScheduleService schedules=mock(ScheduleService.class);
 final ObjectProvider<ScheduleService> provider=mock(ObjectProvider.class);
 final ContractService service=new ContractService(contracts,versions,pages,null,null,null,null,provider);
 Contract contract(){var c=Contract.create(user,"EMPLOYMENT");ReflectionTestUtils.setField(c,"contractId",10L);return c;}
 @Test void versionUsesPageRowsRatherThanStoredPageCount(){
  var v=ContractVersion.create(10L,99);ReflectionTestUtils.setField(v,"versionNo",2);
  when(versions.findById(20L)).thenReturn(Optional.of(v));when(contracts.findByContractIdAndUserIdAndDeletedAtIsNull(10L,user)).thenReturn(Optional.of(contract()));
  when(pages.findByVersionIdOrderByPageNoAsc(20L)).thenReturn(List.of(ContractPage.create(20L,1,"one",1),ContractPage.create(20L,2,"two",2)));
  var result=service.getVersionOwner(user,20L);assertEquals(10L,result.contractId());assertEquals(2,result.versionNo());assertEquals(2,result.pageCount());assertEquals(3,result.maskedCount());assertNull(result.title());
  verify(contracts,never()).lockActive(any());
 }
 @Test void missingForeignOrDeletedVersionIsNotFound(){
  assertEquals(ErrorCode.CONTRACT_NOT_FOUND,assertThrows(BusinessException.class,()->service.getVersionOwner(user,20L)).getErrorCode());
  when(versions.findById(20L)).thenReturn(Optional.of(ContractVersion.create(10L,1)));
  when(contracts.findByContractIdAndUserIdAndDeletedAtIsNull(10L,user)).thenReturn(Optional.empty());
  assertEquals(ErrorCode.CONTRACT_NOT_FOUND,assertThrows(BusinessException.class,()->service.getVersionOwner(user,20L)).getErrorCode());verifyNoInteractions(pages);
 }
 @Test void similarOnlyReturnsArchivedOwnedActiveContract(){
  assertTrue(service.getSimilarContractInfo(user,10L).isEmpty());var c=contract();
  when(contracts.findByContractIdAndUserIdAndDeletedAtIsNull(10L,user)).thenReturn(Optional.of(c));assertTrue(service.getSimilarContractInfo(user,10L).isEmpty());
  ReflectionTestUtils.setField(c,"archived",true);when(provider.getObject()).thenReturn(schedules);when(schedules.countForContract(user,10L)).thenReturn(3);
  var result=service.getSimilarContractInfo(user,10L).orElseThrow();assertEquals(3,result.scheduleCount());assertNull(result.analyzedAt());assertTrue(service.getSimilarContractInfo(UUID.randomUUID(),10L).isEmpty());
  when(contracts.findByContractIdAndUserIdAndDeletedAtIsNull(10L,user)).thenReturn(Optional.empty());assertTrue(service.getSimilarContractInfo(user,10L).isEmpty());
 }
 @Test void checkLevelReturnsDatabaseValue() throws Exception {
  var repo=mock(DocumentTypeRepository.class);var service=new DocumentTypeService(null,repo);var ctor=DocumentType.class.getDeclaredConstructor();ctor.setAccessible(true);var type=ctor.newInstance();ReflectionTestUtils.setField(type,"checkLevel","A");when(repo.findById("EMPLOYMENT")).thenReturn(Optional.of(type));assertEquals("A",service.getCheckLevel("EMPLOYMENT"));
  assertEquals(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE,assertThrows(BusinessException.class,()->service.getCheckLevel("MISSING")).getErrorCode());
 }
 @Test void findArchivedSameTypeAndArchiveAndListAndDetail(){
  var c=contract();ReflectionTestUtils.setField(c,"archived",true);ReflectionTestUtils.setField(c,"title","알바 계약서");
  var v=ContractVersion.create(10L,2);ReflectionTestUtils.setField(v,"versionId",20L);ReflectionTestUtils.setField(v,"latestAnalysisId",88L);
  when(contracts.findByUserIdAndDocumentTypeCodeAndArchivedTrueAndDeletedAtIsNullOrderByContractIdDesc(user,"EMPLOYMENT")).thenReturn(List.of(c));
  when(versions.findByContractIdAndVersionNo(10L,1)).thenReturn(Optional.of(v));
  var same=service.findArchivedSameType(user,"EMPLOYMENT",99L);
  assertEquals(1,same.size());assertEquals(10L,same.get(0).contractId());assertEquals(88L,same.get(0).latestAnalysisId());
  assertTrue(service.findArchivedSameType(user,"EMPLOYMENT",10L).isEmpty());

  when(contracts.findByUserIdAndArchivedTrueAndDeletedAtIsNullOrderByUpdatedAtDescContractIdDesc(user)).thenReturn(List.of(c));
  var myList=service.listMyContracts(user,"ALL",0,20);
  assertEquals(1,myList.items().size());assertEquals("알바 계약서",myList.items().get(0).title());
  assertEquals(1,myList.categoryCounts().get("ALL"));

  when(contracts.findByContractIdAndUserIdAndDeletedAtIsNull(10L,user)).thenReturn(Optional.of(c));
  when(versions.findByContractIdOrderByVersionNoDesc(10L)).thenReturn(List.of(v));
  var detail=service.getMyContractDetail(user,10L);
  assertEquals(10L,detail.contractId());assertEquals(1,detail.versions().size());assertEquals(88L,detail.versions().get(0).analysisId());
 }
}
