package com.team.docai.web.comparison.dto.response;
import com.team.docai.domain.comparison.dto.ComparisonInfo;
import java.util.List;
public record ComparisonResponse(Long comparisonId,String status,String compatibility,Counts counts,List<ChangeItem> changes,List<UncomparedItem> uncompared,String unchangedSummary){
 public record Counts(int changed,int added,int manual,int uncompared){}
 public record ChangeItem(Long changeId,String type,String category,String label,String before,String after,String note,String source){}
 public record UncomparedItem(Long uncomparedId,String label,String location,String reason,String previousText){}
 public static ComparisonResponse from(ComparisonInfo i){
  return new ComparisonResponse(i.comparisonId(),i.status(),i.compatibility(),
   new Counts(i.counts().changed(),i.counts().added(),i.counts().manual(),i.counts().uncompared()),
   i.changes().stream().map(c->new ChangeItem(c.changeId(),c.type(),c.category(),c.label(),c.before(),c.after(),c.note(),c.source())).toList(),
   i.uncompared().stream().map(u->new UncomparedItem(u.uncomparedId(),u.label(),u.location(),u.reason(),u.previousText())).toList(),
   i.unchangedSummary());
 }
}
