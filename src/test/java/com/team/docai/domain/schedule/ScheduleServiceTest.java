package com.team.docai.domain.schedule;
import com.team.docai.domain.schedule.entity.Schedule;
import com.team.docai.domain.schedule.repository.ScheduleRepository;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.domain.contract.dto.ContractSummaryInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ScheduleServiceTest {
 final UUID user=UUID.randomUUID();
 final ScheduleRepository repo=mock(ScheduleRepository.class);
 final ContractService contracts=mock(ContractService.class);
 final ScheduleService service=new ScheduleService(repo,contracts,Clock.fixed(Instant.parse("2026-11-17T15:01:00Z"),ZoneOffset.UTC));
 Schedule row(Long contract) throws Exception {
  var c=Schedule.class.getDeclaredConstructor();c.setAccessible(true);var s=c.newInstance();
  ReflectionTestUtils.setField(s,"scheduleId",1L);ReflectionTestUtils.setField(s,"contractId",contract);ReflectionTestUtils.setField(s,"date",LocalDate.of(2026,11,18));return s;
 }
 @Test void seoulMidnightAndDeletedContractHandling() throws Exception {
  when(repo.findByUserIdAndDateGreaterThanEqualOrderByDateAscScheduleIdAsc(eq(user),eq(LocalDate.of(2026,11,18)),any(Pageable.class))).thenReturn(List.of(row(5L)));
  when(contracts.getSummary(user,5L)).thenReturn(new ContractSummaryInfo(5L,"계약서",true,null));
  var info=service.upcoming(user,2).get(0);assertEquals(0,info.dDay());assertEquals("계약서",info.contractTitle());
  when(contracts.getSummary(user,5L)).thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  assertNull(service.upcoming(user,2).get(0).contractId());
  doThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)).when(contracts).getSummary(user,5L);
  assertThrows(BusinessException.class,()->service.upcoming(user,2));
 }
 @Test void invalidLimitDoesNotQueryAndDeletionRequiresOwner() throws Exception {
  for(int n:List.of(0,11,-1))assertThrows(BusinessException.class,()->service.upcoming(user,n));verifyNoInteractions(repo);
  when(repo.lockOwned(user,1L)).thenReturn(Optional.empty());
  assertEquals(ErrorCode.SCHEDULE_NOT_FOUND,assertThrows(BusinessException.class,()->service.delete(user,1L)).getErrorCode());verify(repo,never()).delete(any());
  var row=row(null);when(repo.lockOwned(user,1L)).thenReturn(Optional.of(row));service.delete(user,1L);verify(repo).delete(row);
 }
}
