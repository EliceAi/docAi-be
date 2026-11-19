package com.team.docai.domain.comparison.dto;
import java.time.LocalDate;
import java.util.List;
public record ConfirmComparisonCommand(List<ScheduleItem> schedules){
 public record ScheduleItem(String action,Long scheduleId,Long candidateId,String title,LocalDate date){}
}
