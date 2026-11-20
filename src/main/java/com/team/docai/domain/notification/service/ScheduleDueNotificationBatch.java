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
 * Asia/Seoul 기준 매일 오전 9시 실행, D-7 / D-1 / D-0(당일) 알림 생성.
 * 건별 schedule 행 잠금(PESSIMISTIC_WRITE) 후 날짜 재검증으로 배치 실행 중 일정 수정·삭제 동시성 충돌을 방지한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix="docai.batch.schedule-due",name="enabled",havingValue="true",matchIfMissing=true)
public class ScheduleDueNotificationBatch {
 static final List<Integer> DEFAULT_DUE_OFFSETS=List.of(7,1,0);
 private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");

 private final ScheduleService schedules;
 private final ContractService contracts;
 private final Clock clock;
 private final List<Integer> dueOffsets;

 @Autowired
 public ScheduleDueNotificationBatch(
  ScheduleService schedules,
  ContractService contracts,
  Clock clock,
  @Value("${docai.batch.schedule-due.offsets:7,1,0}") List<Integer> dueOffsets
 ){
  this.schedules=schedules;
  this.contracts=contracts;
  this.clock=clock;
  this.dueOffsets=dueOffsets==null||dueOffsets.isEmpty()?DEFAULT_DUE_OFFSETS:dueOffsets.stream().filter(o->o!=null&&o>=0).distinct().toList();
 }

 @Scheduled(cron="${docai.batch.schedule-due.cron:0 0 9 * * *}",zone="Asia/Seoul")
 public int run(){
  if(dueOffsets.isEmpty())return 0;
  LocalDate today=LocalDate.now(clock.withZone(SEOUL));
  List<LocalDate> targetDates=dueOffsets.stream().map(today::plusDays).toList();
  List<ScheduleDueTargetInfo> targets=schedules.findDueTargets(targetDates);
  int created=0;
  for(ScheduleDueTargetInfo s:targets){
   int offset=(int)ChronoUnit.DAYS.between(today,s.date());
   if(!dueOffsets.contains(offset))continue;
   try{
    boolean inserted=schedules.createDueNotificationIfMatches(
     s.scheduleId(),
     s.date(),
     offset,
     (dateKind,scheduleTitle)->formatTitle(offset,dateKind,scheduleTitle),
     this::resolveContractTitle
    );
    if(inserted)created++;
   }catch(DataIntegrityViolationException e){
    log.debug("이미 생성된 일정 알림 건너뜀: scheduleId={}, offset={}, dueDate={}",s.scheduleId(),offset,s.date());
   }catch(RuntimeException e){
    log.warn("일정 알림 생성 실패: scheduleId={}, error={}",s.scheduleId(),e.toString());
   }
  }
  if(created>0)log.info("일정 알림 배치 완료: today={}, created={}",today,created);
  return created;
 }

 static boolean isMoveInSchedule(String dateKind,String scheduleTitle){
  if("MOVE_IN".equals(dateKind))return true;
  if(scheduleTitle==null)return false;
  return scheduleTitle.contains("입주")||scheduleTitle.contains("전입신고");
 }

 static String formatTitle(int offset,String dateKind,String scheduleTitle){
  if(isMoveInSchedule(dateKind,scheduleTitle)){
   if(offset==0)return "오늘 입주·전입신고 마감일이에요";
   return "D-"+offset+" · 입주,전입신고가 다가와요";
  }
  String safeName=scheduleTitle==null||scheduleTitle.isBlank()?"일정":scheduleTitle.strip();
  if(offset==0)return "오늘 "+safeName+" 마감일이에요";
  return "D-"+offset+" · "+safeName+subjectParticle(safeName)+" 다가와요";
 }

 private static String subjectParticle(String word){
  if(word==null||word.isEmpty())return "가";
  char last=word.charAt(word.length()-1);
  if(last>=0xAC00&&last<=0xD7A3){
   return (last-0xAC00)%28!=0?"이":"가";
  }
  return "이(가)";
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
