package com.team.docai.web.comparison.dto.response;
import com.team.docai.domain.comparison.dto.ComparisonManualInputInfo;
public record ComparisonManualInputResponse(Long changeId,String type,String category,String label,String before,String after,String source){
 public static ComparisonManualInputResponse from(ComparisonManualInputInfo i){
  return new ComparisonManualInputResponse(i.changeId(),i.type(),i.category(),i.label(),i.before(),i.after(),i.source());
 }
}
