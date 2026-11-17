package com.team.docai.domain.contract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.contract.entity.*;
import com.team.docai.domain.contract.repository.*;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.global.storage.FileStorage;
import com.team.docai.global.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ContractPageReadTest {
 final ContractRepository contracts=mock(ContractRepository.class);
 final ContractVersionRepository versions=mock(ContractVersionRepository.class);
 final ContractPageRepository pages=mock(ContractPageRepository.class);
 final FileStorage storage=mock(FileStorage.class);
 final ContractService service=new ContractService(contracts,versions,pages,mock(DocumentTypeService.class),storage,mock(JdbcTemplate.class),new ObjectMapper());
 final UUID user=UUID.randomUUID();
 @Test void checksOwnerBeforeReadingAnyImageOrVersion(){
  when(contracts.findByContractIdAndUserIdAndDeletedAtIsNull(1L,user)).thenReturn(Optional.empty());
  assertEquals(ErrorCode.CONTRACT_NOT_FOUND,assertThrows(BusinessException.class,()->service.readPage(user,1L,1,null)).getErrorCode());
  verifyNoInteractions(versions,pages,storage);
 }
 @Test void latestAndExplicitVersionAndMissingPage(){
  var contract=Contract.create(user,"LEASE");ReflectionTestUtils.setField(contract,"currentVersionNo",2);
  when(contracts.findByContractIdAndUserIdAndDeletedAtIsNull(1L,user)).thenReturn(Optional.of(contract));
  var version=ContractVersion.create(1L,1);ReflectionTestUtils.setField(version,"versionId",20L);
  when(versions.findByContractIdAndVersionNo(1L,2)).thenReturn(Optional.of(version));
  var page=ContractPage.create(20L,1,"contracts/image.jpg",0);
  when(pages.findByVersionIdAndPageNo(20L,1)).thenReturn(Optional.of(page));byte[] bytes={1,2};when(storage.read(page.getImageKey())).thenReturn(bytes);
  assertArrayEquals(bytes,service.readPage(user,1L,1,null));
  assertEquals(ErrorCode.VERSION_NOT_FOUND,assertThrows(BusinessException.class,()->service.readPage(user,1L,1,3)).getErrorCode());
  assertEquals(ErrorCode.PAGE_NOT_FOUND,assertThrows(BusinessException.class,()->service.readPage(user,1L,2,2)).getErrorCode());
 }
}
