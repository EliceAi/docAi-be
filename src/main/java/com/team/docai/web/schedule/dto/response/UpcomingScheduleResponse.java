package com.team.docai.web.schedule.dto.response;
import com.team.docai.domain.schedule.dto.UpcomingScheduleInfo;
import java.time.LocalDate;
public record UpcomingScheduleResponse(Long scheduleId,String title,LocalDate date,long dDay,Long contractId,String contractTitle) {
 public static UpcomingScheduleResponse from(UpcomingScheduleInfo i){return new UpcomingScheduleResponse(i.scheduleId(),i.title(),i.date(),i.dDay(),i.contractId(),i.contractTitle());}
}
