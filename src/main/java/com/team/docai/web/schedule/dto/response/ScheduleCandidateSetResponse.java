package com.team.docai.web.schedule.dto.response;
import com.team.docai.domain.schedule.dto.ScheduleCandidateSetInfo;
import java.time.LocalDate;
import java.util.List;
public record ScheduleCandidateSetResponse(Long contractId,String contractTitle,List<CandidateItem> candidates,List<MissingItem> missing){
 public record CandidateItem(Long candidateId,String title,LocalDate date,String source,String location,Integer pageNo,boolean selected,String conflictGroup){}
 public record MissingItem(Long candidateId,String title,String message){}
 public static ScheduleCandidateSetResponse from(ScheduleCandidateSetInfo i){
  return new ScheduleCandidateSetResponse(i.contractId(),i.contractTitle(),
   i.candidates().stream().map(c->new CandidateItem(c.candidateId(),c.title(),c.date(),c.source(),c.location(),c.pageNo(),c.selected(),c.conflictGroup())).toList(),
   i.missing().stream().map(m->new MissingItem(m.candidateId(),m.title(),m.message())).toList());
 }
}
