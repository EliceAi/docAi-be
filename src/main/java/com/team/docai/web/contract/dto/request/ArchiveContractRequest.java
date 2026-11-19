package com.team.docai.web.contract.dto.request;
import com.team.docai.domain.contract.dto.ArchiveContractCommand;
import com.team.docai.global.error.*;
import java.time.LocalDate;
import java.util.*;
public record ArchiveContractRequest(List<ScheduleRequest> schedules){
 public record ScheduleRequest(Long candidateId,String title,LocalDate date,String source){}
 public ArchiveContractCommand toCommand(){
  if(schedules==null)return new ArchiveContractCommand(List.of());
  List<ArchiveContractCommand.ScheduleItem> items=new ArrayList<>();
  for(var s:schedules){
   if(s==null||s.title()==null||s.title().isBlank()||s.title().codePointCount(0,s.title().length())>50||s.date()==null)
    throw new BusinessException(ErrorCode.INVALID_INPUT);
   String src=s.source()==null?"MANUAL":s.source().strip().toUpperCase(Locale.ROOT);
   if(!Set.of("WRITTEN","MANUAL","REVIEW").contains(src))throw new BusinessException(ErrorCode.INVALID_INPUT);
   items.add(new ArchiveContractCommand.ScheduleItem(s.candidateId(),s.title().strip(),s.date(),src));
  }
  return new ArchiveContractCommand(items);
 }
}
