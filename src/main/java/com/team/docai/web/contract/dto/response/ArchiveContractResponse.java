package com.team.docai.web.contract.dto.response;
import com.team.docai.domain.contract.dto.ArchiveContractInfo;
import java.time.LocalDate;
import java.util.List;
public record ArchiveContractResponse(Long contractId,boolean archived,int version,List<SavedSchedule> savedSchedules){
 public record SavedSchedule(Long scheduleId,String title,LocalDate date){}
 public static ArchiveContractResponse from(ArchiveContractInfo i){
  return new ArchiveContractResponse(i.contractId(),i.archived(),i.version(),
   i.savedSchedules().stream().map(s->new SavedSchedule(s.scheduleId(),s.title(),s.date())).toList());
 }
}
