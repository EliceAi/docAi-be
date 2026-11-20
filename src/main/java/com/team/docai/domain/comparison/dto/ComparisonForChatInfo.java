package com.team.docai.domain.comparison.dto;
import java.util.List;
public record ComparisonForChatInfo(Long comparisonId,Long previousContractId,String status,String unchangedSummary,List<Change> changes){
 public record Change(String type,String category,String label,String before,String after,String note){}
}
