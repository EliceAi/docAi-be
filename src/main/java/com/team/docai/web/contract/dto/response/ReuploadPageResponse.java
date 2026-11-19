package com.team.docai.web.contract.dto.response;
import com.team.docai.domain.contract.dto.ReuploadPageInfo;
public record ReuploadPageResponse(Long contractId,int pageNo,int pageCount,String status){
 public static ReuploadPageResponse from(ReuploadPageInfo i){return new ReuploadPageResponse(i.contractId(),i.pageNo(),i.pageCount(),i.status());}
}
