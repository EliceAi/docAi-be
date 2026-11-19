package com.team.docai.domain.comparison.dto;
import java.time.LocalDate;
import java.util.List;
public record ConfirmComparisonInfo(Long contractId,int version,int previousVersion,List<SavedSchedule> savedSchedules){
 public record SavedSchedule(Long scheduleId,String title,LocalDate date){}
}
