package com.team.docai.domain.contract.dto;
import java.time.OffsetDateTime;
import java.util.*;
public record MyContractsInfo(List<Item> items,Map<String,Integer> categoryCounts,int page,int size,boolean hasNext){
 public record Item(Long contractId,String title,String documentTypeCode,String documentTypeName,String category,OffsetDateTime analyzedAt,int version,OffsetDateTime previousVersionAnalyzedAt,int scheduleCount,long needsCheckCount,long missingCount){}
}
