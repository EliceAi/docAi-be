package com.team.docai.domain.schedule.dto;
import java.time.*;
public record ScheduleDetailInfo(Long scheduleId,String title,LocalDate date,long dDay,String source,String location,ContractRef contract){
 public record ContractRef(Long contractId,String title,OffsetDateTime analyzedAt){}
}
