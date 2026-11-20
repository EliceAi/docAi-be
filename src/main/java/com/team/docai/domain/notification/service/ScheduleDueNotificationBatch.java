package com.team.docai.domain.notification.service;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.schedule.dto.ScheduleDueTargetInfo;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.global.error.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 일정 알림 배치 (D-N).
 * 실행 시각(cron)과 알림 시점(offsets)은 명세상 팀 협의 항목이므로 임의 기본값을 강제하지 않는다.
 * 팀 협의 후 docai.batch.schedule-due.enabled=true, cron, offsets를 설정하면 동작한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix="docai.batch.schedule-due",name="enabled",havingValue="true",matchIfMissing=false)
public class ScheduleDueNotificationBatch {
 private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");

 private final ScheduleService schedules;
 private final ContractService contracts;
 private final NotificationService notifications;
 private final Clock clock;
 private final List<Integer> dueOffsets;

 @Autowired
 public ScheduleDueNotificationBatch(
  ScheduleService schedules,
  ContractService contracts,
  NotificationService notifications,
  Clock clock,
  @Value("${docai.batch.schedule-due.offsets:}") List<Integer> dueOffsets
 ){
  this.schedules=schedules;
  this.contracts=contracts;
  this.notifications=notifications;
  this.clock=clock;
  this.dueOffsets=dueOffsets==null?List.of():dueOffsets.stream().filter(o->o!=null&&o>=0).distinct().toList();
 }

 @Scheduled(cron="${docai.batch.schedule-due.cron:-}",zone="Asia/Seoul")
 public int run(){
  if(dueOffsets.isEmpty())return 0;
  LocalDate today=LocalDate.now(clock.withZone(SEOUL));
  List<LocalDate> targetDates=dueOffsets.stream().map(today::plusDays).toList();
  List<ScheduleDueTargetInfo> targets=schedules.findDueTargets(targetDates);
  int created=0;
  for(ScheduleDueTargetInfo s:targets){
   int offset=(int)ChronoUnit.DAYS.between(today,s.date());
   if(!dueOffsets.contains(offset))continue;
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
  return "D-"+offset+" · "+scheduleTitle;
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
