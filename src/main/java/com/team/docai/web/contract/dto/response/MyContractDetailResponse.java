package com.team.docai.web.contract.dto.response;
import com.team.docai.domain.contract.dto.MyContractDetailInfo;
import java.time.*;
import java.util.List;
public record MyContractDetailResponse(Long contractId,String title,String documentTypeCode,String documentTypeName,String checkLevel,List<VersionItem> versions,Summary summary,List<KeyTermItem> keyTerms,List<ScheduleItem> schedules){
 public record VersionItem(int version,Long analysisId,OffsetDateTime analyzedAt,boolean latest){}
 public record Summary(long needsCheckCount,long missingCount,long totalClauseCount){}
 public record KeyTermItem(String label,String value,String state,String source){}
 public record ScheduleItem(Long scheduleId,String title,LocalDate date,long dDay){}
 public static MyContractDetailResponse from(MyContractDetailInfo i){
  return new MyContractDetailResponse(i.contractId(),i.title(),i.documentTypeCode(),i.documentTypeName(),i.checkLevel(),
   i.versions().stream().map(v->new VersionItem(v.version(),v.analysisId(),v.analyzedAt(),v.latest())).toList(),
   new Summary(i.summary().needsCheckCount(),i.summary().missingCount(),i.summary().totalClauseCount()),
   i.keyTerms().stream().map(k->new KeyTermItem(k.label(),k.value(),k.state(),k.source())).toList(),
   i.schedules().stream().map(s->new ScheduleItem(s.scheduleId(),s.title(),s.date(),s.dDay())).toList());
 }
}
