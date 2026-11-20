package com.team.docai.domain.notification;
import com.team.docai.domain.contract.dto.ContractSummaryInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.domain.notification.service.ScheduleDueNotificationBatch;
import com.team.docai.domain.schedule.dto.ScheduleDueTargetInfo;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.global.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduleDueNotificationBatchTest {
 final UUID user=UUID.randomUUID();
 final ScheduleService schedules=mock(ScheduleService.class);
 final ContractService contracts=mock(ContractService.class);
 final NotificationService notifications=mock(NotificationService.class);
 // 2026-11-18 09:00 Asia/Seoul == 2026-11-18 00:00 UTC
 final Clock clock=Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"),ZoneOffset.UTC);
 final ScheduleDueNotificationBatch batch=new ScheduleDueNotificationBatch(schedules,contracts,notifications,clock);

 @Test void createsD7D1D0AndHandlesDeletedContractAndDuplicates() {
  LocalDate d0=LocalDate.of(2026,11,18);
  LocalDate d1=LocalDate.of(2026,11,19);
  LocalDate d7=LocalDate.of(2026,11,25);
  when(schedules.findDueTargets(List.of(d7,d1,d0))).thenReturn(List.of(
   new ScheduleDueTargetInfo(1L,user,10L,"잔금일",d7),
   new ScheduleDueTargetInfo(2L,user,11L,"입주일",d1),
   new ScheduleDueTargetInfo(3L,user,null,"만기일",d0),
   new ScheduleDueTargetInfo(4L,user,10L,"중복일정",d0)
  ));
  when(contracts.getSummary(user,10L)).thenReturn(new ContractSummaryInfo(10L,"원룸 계약서",true,null));
  when(contracts.getSummary(user,11L)).thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  when(notifications.createScheduleDue(user,1L,7,d7,"D-7 · 잔금일","원룸 계약서")).thenReturn(true);
  when(notifications.createScheduleDue(user,2L,1,d1,"D-1 · 입주일",null)).thenReturn(true);
  when(notifications.createScheduleDue(user,3L,0,d0,"D-day · 만기일",null)).thenThrow(new DataIntegrityViolationException("uq_notification_due"));
  when(notifications.createScheduleDue(user,4L,0,d0,"D-day · 중복일정","원룸 계약서")).thenReturn(false);

  int created=batch.run();
  assertEquals(2,created);
  verify(notifications).createScheduleDue(user,1L,7,d7,"D-7 · 잔금일","원룸 계약서");
  verify(notifications).createScheduleDue(user,2L,1,d1,"D-1 · 입주일",null);
  verify(notifications).createScheduleDue(user,3L,0,d0,"D-day · 만기일",null);
  verify(notifications).createScheduleDue(user,4L,0,d0,"D-day · 중복일정","원룸 계약서");
 }
}
