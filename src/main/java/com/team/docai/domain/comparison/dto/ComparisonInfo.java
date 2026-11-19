package com.team.docai.domain.comparison.dto;
import java.util.List;
public record ComparisonInfo(Long comparisonId,String status,String compatibility,Counts counts,List<ChangeItem> changes,List<UncomparedItem> uncompared,String unchangedSummary){
 public record Counts(int changed,int added,int manual,int uncompared){}
 public record ChangeItem(Long changeId,String type,String category,String label,String before,String after,String note,String source){}
 public record UncomparedItem(Long uncomparedId,String label,String location,String reason,String previousText){}
}
