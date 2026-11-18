package com.team.docai.domain.schedule.service;
import com.team.docai.domain.schedule.dto.*;
import com.team.docai.domain.schedule.repository.ScheduleRepository;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.error.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ScheduleService {
 private final ScheduleRepository schedules;
 private final ContractService contracts;
 private final Clock clock;
 public int countForContract(UUID userId,Long contractId){return Math.toIntExact(schedules.countByUserIdAndContractId(userId,contractId));}
 public List<UpcomingScheduleInfo> upcoming(UUID userId,int limit){
  if(limit<1||limit>10)throw new BusinessException(ErrorCode.INVALID_INPUT);
  var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
  return schedules.findByUserIdAndDateGreaterThanEqualOrderByDateAscScheduleIdAsc(userId,today,PageRequest.of(0,limit)).stream().map(s->{
   Long contractId=s.getContractId();String title=null;
   if(contractId!=null){try{title=contracts.getSummary(userId,contractId).title();}catch(BusinessException e){
    if(e.getErrorCode()!=ErrorCode.CONTRACT_NOT_FOUND)throw e;contractId=null;
   }}
   return new UpcomingScheduleInfo(s.getScheduleId(),s.getTitle(),s.getDate(),ChronoUnit.DAYS.between(today,s.getDate()),contractId,title);
  }).toList();
 }
 @Transactional public void delete(UUID userId,Long scheduleId){
  var schedule=schedules.lockOwned(userId,scheduleId).orElseThrow(()->new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
  schedules.delete(schedule); // Existing FK deletes notifications for this schedule only.
 }
}
