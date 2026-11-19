package com.team.docai.web.comparison.dto.request;
import com.team.docai.domain.comparison.dto.ComparisonManualInputCommand;
import com.team.docai.global.error.*;
public record ComparisonManualInputRequest(Long uncomparedId,String text){
 public ComparisonManualInputCommand toCommand(){
  if(uncomparedId==null||uncomparedId<=0||text==null||text.isBlank())throw new BusinessException(ErrorCode.INVALID_INPUT);
  return new ComparisonManualInputCommand(uncomparedId,text.strip());
 }
}
