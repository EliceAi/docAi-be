package com.team.docai.domain.contract.dto;
import java.util.List;
import java.time.OffsetDateTime;
public record ContractForAnalysisInfo(Long contractId,Long versionId,String documentTypeCode,boolean archived,List<PageInfo> pages){
 public record PageInfo(int pageNo,String imageKey,OffsetDateTime replacedAt){}
}
