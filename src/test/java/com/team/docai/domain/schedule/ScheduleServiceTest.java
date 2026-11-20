package com.team.docai.domain.schedule;
import com.team.docai.domain.analysis.dto.AnalysisRefInfo;
import com.team.docai.domain.analysis.dto.ScheduleCandidateInfo;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.schedule.dto.*;
import com.team.docai.domain.schedule.entity.Schedule;
import com.team.docai.domain.schedule.entity.ScheduleSource;
import com.team.docai.domain.schedule.repository.ScheduleRepository;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.domain.contract.dto.ContractSummaryInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.global.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
 final AnalysisService analysis=mock(AnalysisService.class);
 final NotificationService notifications=mock(NotificationService.class);
 final ObjectProvider<AnalysisService> analysisProvider=mock(ObjectProvider.class);
 final ObjectProvider<NotificationService> notifProvider=mock(ObjectProvider.class);
 final ScheduleService service=new ScheduleService(repo,contracts,Clock.fixed(Instant.parse("2026-11-17T15:01:00Z"),ZoneOffset.UTC),analysisProvider,notifProvider);
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
 @Test void detailAndUpdateHandleDateChangeAndNotifications() throws Exception {
  var s=row(5L);ReflectionTestUtils.setField(s,"title","입주");ReflectionTestUtils.setField(s,"source",ScheduleSource.WRITTEN);ReflectionTestUtils.setField(s,"location","1쪽 제2조");
  when(repo.findByScheduleIdAndUserId(1L,user)).thenReturn(Optional.of(s));
  when(contracts.getSummary(user,5L)).thenReturn(new ContractSummaryInfo(5L,"원룸 계약서",true,null));
  var detail=service.getDetail(user,1L);
  assertEquals("입주",detail.title());assertEquals("WRITTEN",detail.source());assertEquals(5L,detail.contract().contractId());

  when(repo.lockOwned(user,1L)).thenReturn(Optional.of(s));
  when(notifProvider.getIfAvailable()).thenReturn(notifications);
  var updated=service.update(user,1L,new UpdateScheduleCommand("입주·전입신고",LocalDate.of(2026,11,25)));
  assertEquals("입주·전입신고",updated.title());
  assertEquals(LocalDate.of(2026,11,25),updated.date());
  assertEquals("MANUAL",updated.source());
  assertNull(updated.location());
  verify(notifications).deleteUnreadForScheduleDate(1L,LocalDate.of(2026,11,18));
 }
 @Test void getCandidatesUsesAnalysisGetRefDirectly() {
  when(analysisProvider.getIfAvailable()).thenReturn(analysis);
  when(analysis.getRef(user,100L)).thenReturn(new AnalysisRefInfo(100L,10L,5L,"COMPLETED",OffsetDateTime.parse("2026-11-17T15:00:00Z")));
  when(contracts.getSummary(user,5L)).thenReturn(new ContractSummaryInfo(5L,"원룸 계약서",false,100L));
  when(analysis.getScheduleCandidates(100L)).thenReturn(List.of(
   new ScheduleCandidateInfo(1L,"MOVE_IN","입주일",LocalDate.of(2026,12,1),"WRITTEN","1쪽 제2조",1,null,true),
   new ScheduleCandidateInfo(2L,"BALANCE","잔금일",null,"WRITTEN",null,null,null,false)
  ));
  var result=service.getCandidates(user,100L);
  assertEquals(5L,result.contractId());
  assertEquals("원룸 계약서",result.contractTitle());
  assertEquals(1,result.candidates().size());
  assertEquals(1,result.missing().size());
  assertEquals("계약서에 없어요.",result.missing().get(0).message());

  when(analysis.getRef(user,101L)).thenReturn(new AnalysisRefInfo(101L,10L,5L,"ANALYZING",null));
  assertEquals(ErrorCode.CONTRACT_NOT_ANALYZED,assertThrows(BusinessException.class,()->service.getCandidates(user,101L)).getErrorCode());

  when(analysis.getRef(user,102L)).thenThrow(new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
  assertEquals(ErrorCode.ANALYSIS_NOT_FOUND,assertThrows(BusinessException.class,()->service.getCandidates(user,102L)).getErrorCode());
 }
 @Test void createDueNotificationIfMatchesGuardsConcurrentDateChangeAndDeletion() throws Exception {
  when(notifProvider.getIfAvailable()).thenReturn(notifications);
  var s=row(5L);
  ReflectionTestUtils.setField(s,"userId",user);
  ReflectionTestUtils.setField(s,"dateKind","MOVE_IN");
  ReflectionTestUtils.setField(s,"title","입주·전입신고");
  LocalDate expectedDate=LocalDate.of(2026,11,18);

  when(repo.lockById(1L)).thenReturn(Optional.of(s));
  when(notifications.createScheduleDue(user,1L,0,expectedDate,"오늘 입주·전입신고 마감일이에요","원룸 계약서")).thenReturn(true);
  assertTrue(service.createDueNotificationIfMatches(1L,expectedDate,0,(dk,t)->"오늘 입주·전입신고 마감일이에요","원룸 계약서"));

  // Date concurrently changed before lock acquired -> skip without creating stale notification
  ReflectionTestUtils.setField(s,"date",LocalDate.of(2026,11,25));
  assertFalse(service.createDueNotificationIfMatches(1L,expectedDate,0,(dk,t)->"오늘 입주·전입신고 마감일이에요","원룸 계약서"));

  // Schedule concurrently deleted -> skip
  when(repo.lockById(2L)).thenReturn(Optional.empty());
  assertFalse(service.createDueNotificationIfMatches(2L,expectedDate,0,(dk,t)->"오늘 입주·전입신고 마감일이에요","원룸 계약서"));

  verify(notifications,times(1)).createScheduleDue(any(),any(),anyInt(),any(),any(),any());
 }
}
