package com.team.docai.web.comparison.dto.response;
import com.team.docai.domain.comparison.dto.ComparisonPreviewInfo;
import java.time.OffsetDateTime;
public record ComparisonPreviewResponse(String compatibility,String reason,DocSummary previous,DocSummary revised){
 public record DocSummary(Long contractId,String title,String documentTypeCode,int version,int pageCount,OffsetDateTime analyzedAt){}
 public static ComparisonPreviewResponse from(ComparisonPreviewInfo i){
  return new ComparisonPreviewResponse(i.compatibility(),i.reason(),
   i.previous()==null?null:new DocSummary(i.previous().contractId(),i.previous().title(),i.previous().documentTypeCode(),i.previous().version(),i.previous().pageCount(),i.previous().analyzedAt()),
   i.revised()==null?null:new DocSummary(i.revised().contractId(),i.revised().title(),i.revised().documentTypeCode(),i.revised().version(),i.revised().pageCount(),i.revised().analyzedAt()));
 }
}
