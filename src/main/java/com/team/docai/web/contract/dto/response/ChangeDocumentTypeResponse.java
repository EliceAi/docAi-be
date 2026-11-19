package com.team.docai.web.contract.dto.response;
import com.team.docai.domain.contract.dto.ChangeDocumentTypeInfo;
public record ChangeDocumentTypeResponse(Long contractId,String documentTypeCode,String status){
 public static ChangeDocumentTypeResponse from(ChangeDocumentTypeInfo i){return new ChangeDocumentTypeResponse(i.contractId(),i.documentTypeCode(),i.status());}
}
