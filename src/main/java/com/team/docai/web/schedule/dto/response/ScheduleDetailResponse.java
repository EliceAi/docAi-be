package com.team.docai.web.schedule.dto.response;
import com.team.docai.domain.schedule.dto.ScheduleDetailInfo;
import java.time.*;
public record ScheduleDetailResponse(Long scheduleId,String title,LocalDate date,long dDay,String source,String location,ContractRef contract){
 public record ContractRef(Long contractId,String title,OffsetDateTime analyzedAt){}
 public static ScheduleDetailResponse from(ScheduleDetailInfo i){
  return new ScheduleDetailResponse(i.scheduleId(),i.title(),i.date(),i.dDay(),i.source(),i.location(),
   i.contract()==null?null:new ContractRef(i.contract().contractId(),i.contract().title(),i.contract().analyzedAt()));
 }
}
