package com.team.docai.web.comparison.dto.request;
import com.team.docai.domain.comparison.dto.ConfirmComparisonCommand;
import com.team.docai.global.error.*;
import java.time.LocalDate;
import java.util.*;
public record ConfirmComparisonRequest(List<ScheduleItem> schedules){
 public record ScheduleItem(String action,Long scheduleId,Long candidateId,String title,LocalDate date){}
 public ConfirmComparisonCommand toCommand(){
  if(schedules==null)return new ConfirmComparisonCommand(List.of());
  var list=new ArrayList<ConfirmComparisonCommand.ScheduleItem>();
  for(var s:schedules){
   if(s==null||s.action()==null||!Set.of("UPDATE","CREATE","DELETE").contains(s.action()))throw new BusinessException(ErrorCode.INVALID_INPUT);
   list.add(new ConfirmComparisonCommand.ScheduleItem(s.action(),s.scheduleId(),s.candidateId(),s.title(),s.date()));
  }
  return new ConfirmComparisonCommand(list);
 }
}
