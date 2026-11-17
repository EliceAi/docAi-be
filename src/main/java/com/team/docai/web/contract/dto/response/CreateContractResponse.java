package com.team.docai.web.contract.dto.response;
import com.team.docai.domain.contract.dto.CreateContractInfo;
public record CreateContractResponse(Long contractId,String documentTypeCode,int pageCount,String status){
 public static CreateContractResponse from(CreateContractInfo info){return new CreateContractResponse(info.contractId(),info.documentTypeCode(),info.pageCount(),info.status());}
}
