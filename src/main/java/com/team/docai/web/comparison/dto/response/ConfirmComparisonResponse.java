package com.team.docai.web.comparison.dto.response;
import com.team.docai.domain.comparison.dto.ConfirmComparisonInfo;
import java.time.LocalDate;
import java.util.List;
public record ConfirmComparisonResponse(Long contractId,int version,int previousVersion,List<SavedSchedule> savedSchedules){
 public record SavedSchedule(Long scheduleId,String title,LocalDate date){}
 public static ConfirmComparisonResponse from(ConfirmComparisonInfo i){
  return new ConfirmComparisonResponse(i.contractId(),i.version(),i.previousVersion(),
   i.savedSchedules().stream().map(s->new SavedSchedule(s.scheduleId(),s.title(),s.date())).toList());
 }
}
