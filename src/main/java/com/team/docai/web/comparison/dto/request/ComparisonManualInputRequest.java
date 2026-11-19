package com.team.docai.web.comparison.dto.request;
import com.team.docai.domain.comparison.dto.ComparisonManualInputCommand;
import com.team.docai.global.error.*;
public record ComparisonManualInputRequest(Long uncomparedId,String text){
 public ComparisonManualInputCommand toCommand(){
  if(uncomparedId==null||uncomparedId<=0||text==null)throw new BusinessException(ErrorCode.INVALID_INPUT);
  String trimmed=text.strip();
  if(trimmed.isEmpty()||trimmed.codePointCount(0,trimmed.length())>500)throw new BusinessException(ErrorCode.INVALID_INPUT);
  return new ComparisonManualInputCommand(uncomparedId,trimmed);
 }
}
