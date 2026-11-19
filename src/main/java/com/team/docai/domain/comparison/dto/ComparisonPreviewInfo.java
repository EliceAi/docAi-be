package com.team.docai.domain.comparison.dto;
import java.time.OffsetDateTime;
public record ComparisonPreviewInfo(String compatibility,String reason,DocSummary previous,DocSummary revised){
 public record DocSummary(Long contractId,String title,String documentTypeCode,int version,int pageCount,OffsetDateTime analyzedAt){}
}
