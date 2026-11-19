package com.team.docai.domain.schedule.dto;
import java.time.LocalDate;
import java.util.List;
public record ScheduleCandidateSetInfo(Long contractId,String contractTitle,List<CandidateItem> candidates,List<MissingItem> missing){
 public record CandidateItem(Long candidateId,String title,LocalDate date,String source,String location,Integer pageNo,boolean selected,String conflictGroup){}
 public record MissingItem(Long candidateId,String title,String message){}
}
