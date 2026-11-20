package com.team.docai.domain.notification;
import com.team.docai.domain.contract.dto.ContractSummaryInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.notification.service.ScheduleDueNotificationBatch;
import com.team.docai.domain.schedule.dto.ScheduleDueTargetInfo;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.global.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduleDueNotificationBatchTest {
 final UUID user=UUID.randomUUID();
 final ScheduleService schedules=mock(ScheduleService.class);
 final ContractService contracts=mock(ContractService.class);
 // 2026-11-18 09:00 Asia/Seoul == 2026-11-18 00:00 UTC
 final Clock clock=Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"),ZoneOffset.UTC);
 final ScheduleDueNotificationBatch batch=new ScheduleDueNotificationBatch(schedules,contracts,clock,List.of(7,1,0));

 @Test void formatsMoveInAndOtherSchedulesWithoutCrossContamination() {
  LocalDate d0=LocalDate.of(2026,11,18);
  LocalDate d1=LocalDate.of(2026,11,19);
  LocalDate d7=LocalDate.of(2026,11,25);

  var moveInD7=new ScheduleDueTargetInfo(1L,user,10L,"MOVE_IN","입주·전입신고",d7);
  var moveInD1=new ScheduleDueTargetInfo(2L,user,11L,null,"입주일",d1);
  var moveInD0=new ScheduleDueTargetInfo(3L,user,10L,"MOVE_IN","입주·전입신고",d0);
  var otherD7=new ScheduleDueTargetInfo(4L,user,10L,"CONTRACT_END","잔금일",d7);
  var otherD1=new ScheduleDueTargetInfo(5L,user,null,"REPORT_DEADLINE","임대차 신고",d1);
  var otherD0=new ScheduleDueTargetInfo(6L,user,10L,"CONTRACT_END","만기일",d0);

  when(schedules.findDueTargets(List.of(d7,d1,d0))).thenReturn(List.of(moveInD7,moveInD1,moveInD0,otherD7,otherD1,otherD0));
  when(contracts.getSummary(user,10L)).thenReturn(new ContractSummaryInfo(10L,"원룸 계약서",true,null));
  when(contracts.getSummary(user,11L)).thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

  Map<Long,String> generatedTitles=new LinkedHashMap<>();
  Map<Long,String> generatedSubTitles=new LinkedHashMap<>();
  Map<Long,ScheduleDueTargetInfo> targetById=Map.of(
   1L,moveInD7,2L,moveInD1,3L,moveInD0,4L,otherD7,5L,otherD1,6L,otherD0
  );

  when(schedules.createDueNotificationIfMatches(anyLong(),any(),anyInt(),any(),any())).thenAnswer(inv->{
   Long id=inv.getArgument(0);
   BiFunction<String,String,String> titleFormatter=inv.getArgument(3);
   String subTitle=inv.getArgument(4);
   ScheduleDueTargetInfo t=targetById.get(id);
   generatedTitles.put(id,titleFormatter.apply(t.dateKind(),t.title()));
   generatedSubTitles.put(id,subTitle);
   return true;
  });

  int created=batch.run();
  assertEquals(6,created);
  assertEquals("D-7 · 입주,전입신고가 다가와요",generatedTitles.get(1L));
  assertEquals("D-1 · 입주,전입신고가 다가와요",generatedTitles.get(2L));
  assertEquals("오늘 입주·전입신고 마감일이에요",generatedTitles.get(3L));
  assertEquals("D-7 · 잔금일이 다가와요",generatedTitles.get(4L));
  assertEquals("D-1 · 임대차 신고가 다가와요",generatedTitles.get(5L));
  assertEquals("오늘 만기일 마감일이에요",generatedTitles.get(6L));
  assertFalse(generatedTitles.get(4L).contains("입주"));
  assertFalse(generatedTitles.get(5L).contains("입주"));
  assertFalse(generatedTitles.get(6L).contains("입주"));

  assertEquals("원룸 계약서",generatedSubTitles.get(1L));
  assertNull(generatedSubTitles.get(2L));
  assertNull(generatedSubTitles.get(5L));
 }

 @Test void handlesDuplicateAndConcurrentDateChangeGracefully() {
  LocalDate d0=LocalDate.of(2026,11,18);
  LocalDate d1=LocalDate.of(2026,11,19);
  LocalDate d7=LocalDate.of(2026,11,25);
  when(schedules.findDueTargets(List.of(d7,d1,d0))).thenReturn(List.of(
   new ScheduleDueTargetInfo(1L,user,10L,"MOVE_IN","입주·전입신고",d7),
   new ScheduleDueTargetInfo(2L,user,10L,"MOVE_IN","입주·전입신고",d1),
   new ScheduleDueTargetInfo(3L,user,10L,"MOVE_IN","입주·전입신고",d0)
  ));
  when(contracts.getSummary(user,10L)).thenReturn(new ContractSummaryInfo(10L,"원룸 계약서",true,null));
  when(schedules.createDueNotificationIfMatches(eq(1L),eq(d7),eq(7),any(),eq("원룸 계약서"))).thenReturn(true);
  when(schedules.createDueNotificationIfMatches(eq(2L),eq(d1),eq(1),any(),eq("원룸 계약서"))).thenThrow(new DataIntegrityViolationException("uq_notification_due"));
  when(schedules.createDueNotificationIfMatches(eq(3L),eq(d0),eq(0),any(),eq("원룸 계약서"))).thenReturn(false);

  assertEquals(1,batch.run());
 }
}
