package com.team.docai.web.contract.dto.response;
import com.team.docai.domain.contract.dto.MyContractsInfo;
import java.time.OffsetDateTime;
import java.util.*;
public record MyContractsResponse(List<Item> items,Map<String,Integer> categoryCounts,int page,int size,boolean hasNext){
 public record Item(Long contractId,String title,String documentTypeCode,String documentTypeName,String category,OffsetDateTime analyzedAt,int version,OffsetDateTime previousVersionAnalyzedAt,int scheduleCount,long needsCheckCount,long missingCount){}
 public static MyContractsResponse from(MyContractsInfo info){
  return new MyContractsResponse(
   info.items().stream().map(i->new Item(i.contractId(),i.title(),i.documentTypeCode(),i.documentTypeName(),i.category(),i.analyzedAt(),i.version(),i.previousVersionAnalyzedAt(),i.scheduleCount(),i.needsCheckCount(),i.missingCount())).toList(),
   info.categoryCounts(),info.page(),info.size(),info.hasNext());
 }
}
