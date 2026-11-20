package com.team.docai.domain.notification.service;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.schedule.dto.ScheduleDueTargetInfo;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.global.error.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 일정 알림 배치 (D-N). Asia/Seoul 기준 매일 오전 9시에 실행.
 * 명세 예시 기준 D-7, D-1, D-0(당일) 시점에 SCHEDULE_DUE 알림을 생성하며,
 * uq_notification_due(schedule_id, type, due_offset, due_date)로 중복 생성을 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="docai.batch.schedule-due",name="enabled",havingValue="true",matchIfMissing=true)
public class ScheduleDueNotificationBatch {
 static final List<Integer> DUE_OFFSETS=List.of(7,1,0);
 private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");

 private final ScheduleService schedules;
 private final ContractService contracts;
 private final NotificationService notifications;
 private final Clock clock;

 @Scheduled(cron="${docai.batch.schedule-due.cron:0 0 9 * * *}",zone="Asia/Seoul")
 public int run(){
  LocalDate today=LocalDate.now(clock.withZone(SEOUL));
  List<LocalDate> targetDates=DUE_OFFSETS.stream().map(today::plusDays).toList();
  List<ScheduleDueTargetInfo> targets=schedules.findDueTargets(targetDates);
  int created=0;
  for(ScheduleDueTargetInfo s:targets){
   int offset=(int)ChronoUnit.DAYS.between(today,s.date());
   if(!DUE_OFFSETS.contains(offset))continue;
   String title=formatTitle(offset,s.title());
   String subTitle=resolveContractTitle(s);
   try{
    if(notifications.createScheduleDue(s.userId(),s.scheduleId(),offset,s.date(),title,subTitle)){
     created++;
    }
   }catch(DataIntegrityViolationException e){
    log.debug("이미 생성된 일정 알림 건너뜀: scheduleId={}, offset={}, dueDate={}",s.scheduleId(),offset,s.date());
   }catch(RuntimeException e){
    log.warn("일정 알림 생성 실패: scheduleId={}, error={}",s.scheduleId(),e.toString());
   }
  }
  if(created>0)log.info("일정 알림 배치 완료: today={}, created={}",today,created);
  return created;
 }

 static String formatTitle(int offset,String scheduleTitle){
  String prefix=offset==0?"D-day":"D-"+offset;
  return prefix+" · "+scheduleTitle;
 }

 private String resolveContractTitle(ScheduleDueTargetInfo s){
  if(s.contractId()==null)return null;
  try{
   return contracts.getSummary(s.userId(),s.contractId()).title();
  }catch(BusinessException e){
   if(e.getErrorCode()==ErrorCode.CONTRACT_NOT_FOUND)return null;
   throw e;
  }
 }
}
