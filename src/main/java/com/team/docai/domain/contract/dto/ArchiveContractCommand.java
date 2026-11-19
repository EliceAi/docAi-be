package com.team.docai.domain.contract.dto;
import java.time.LocalDate;
import java.util.List;
public record ArchiveContractCommand(List<ScheduleItem> schedules){
 public record ScheduleItem(Long candidateId,String title,LocalDate date,String source){}
}
