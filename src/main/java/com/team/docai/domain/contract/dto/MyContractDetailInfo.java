package com.team.docai.domain.contract.dto;
import java.time.*;
import java.util.List;
public record MyContractDetailInfo(Long contractId,String title,String documentTypeCode,String documentTypeName,String checkLevel,List<VersionItem> versions,Summary summary,List<KeyTermItem> keyTerms,List<ScheduleItem> schedules){
 public record VersionItem(int version,Long analysisId,OffsetDateTime analyzedAt,boolean latest){}
 public record Summary(long needsCheckCount,long missingCount,long totalClauseCount){}
 public record KeyTermItem(String label,String value,String state,String source){}
 public record ScheduleItem(Long scheduleId,String title,LocalDate date,long dDay){}
}
