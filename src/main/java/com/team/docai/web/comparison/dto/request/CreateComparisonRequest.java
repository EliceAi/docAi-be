package com.team.docai.web.comparison.dto.request;
import com.team.docai.domain.comparison.dto.CreateComparisonCommand;
import com.team.docai.global.error.*;
public record CreateComparisonRequest(Long previousContractId,Long revisedContractId){
 public CreateComparisonCommand toCommand(){
  if(previousContractId==null||revisedContractId==null||previousContractId<=0||revisedContractId<=0||previousContractId.equals(revisedContractId))
   throw new BusinessException(ErrorCode.INVALID_INPUT);
  return new CreateComparisonCommand(previousContractId,revisedContractId);
 }
}
