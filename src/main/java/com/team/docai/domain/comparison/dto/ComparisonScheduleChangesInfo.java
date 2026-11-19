package com.team.docai.domain.comparison.dto;
import java.time.LocalDate;
import java.util.List;
public record ComparisonScheduleChangesInfo(String contractTitle,List<ChangeItem> changes){
 public record ChangeItem(Long scheduleId,Long candidateId,String action,String title,LocalDate beforeDate,LocalDate afterDate,String source,String location){}
}
