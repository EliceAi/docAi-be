package com.team.docai.web.comparison.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.docai.domain.comparison.dto.ComparisonScheduleChangesInfo;
import java.time.LocalDate;
import java.util.List;
public record ComparisonScheduleChangesResponse(String contractTitle,List<ChangeItem> changes){
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record ChangeItem(Long scheduleId,Long candidateId,String action,String title,LocalDate beforeDate,LocalDate afterDate,String source,String location){
  @JsonInclude(JsonInclude.Include.ALWAYS) public Long getScheduleId(){return scheduleId;}
  @JsonInclude(JsonInclude.Include.ALWAYS) public LocalDate getBeforeDate(){return beforeDate;}
  @JsonInclude(JsonInclude.Include.ALWAYS) public LocalDate getAfterDate(){return afterDate;}
 }
 public static ComparisonScheduleChangesResponse from(ComparisonScheduleChangesInfo i){
  return new ComparisonScheduleChangesResponse(i.contractTitle(),
   i.changes().stream().map(c->new ChangeItem(c.scheduleId(),c.candidateId(),c.action(),c.title(),c.beforeDate(),c.afterDate(),c.source(),c.location())).toList());
 }
}
