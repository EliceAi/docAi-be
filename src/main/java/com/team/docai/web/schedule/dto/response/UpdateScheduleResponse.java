package com.team.docai.web.schedule.dto.response;
import com.team.docai.domain.schedule.dto.UpdateScheduleInfo;
import java.time.LocalDate;
public record UpdateScheduleResponse(Long scheduleId,String title,LocalDate date,String source){
 public static UpdateScheduleResponse from(UpdateScheduleInfo i){
  return new UpdateScheduleResponse(i.scheduleId(),i.title(),i.date(),i.source());
 }
}
