package com.team.docai.domain.contract.dto;
import java.time.LocalDate;
import java.util.List;
public record ArchiveContractInfo(Long contractId,boolean archived,int version,List<SavedSchedule> savedSchedules){
 public record SavedSchedule(Long scheduleId,String title,LocalDate date){}
}
