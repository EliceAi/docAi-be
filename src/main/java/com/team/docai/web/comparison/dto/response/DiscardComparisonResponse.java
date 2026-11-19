package com.team.docai.web.comparison.dto.response;
import com.team.docai.domain.comparison.dto.DiscardComparisonInfo;
public record DiscardComparisonResponse(Long comparisonId,String status){
 public static DiscardComparisonResponse from(DiscardComparisonInfo i){
  return new DiscardComparisonResponse(i.comparisonId(),i.status());
 }
}
