package com.team.docai.web.schedule.dto.request;
import com.team.docai.domain.schedule.dto.UpdateScheduleCommand;
import com.team.docai.global.error.*;
import java.time.LocalDate;
public record UpdateScheduleRequest(String title,LocalDate date){
 public UpdateScheduleCommand toCommand(){
  if(title==null&&date==null)throw new BusinessException(ErrorCode.INVALID_INPUT);
  String trimmed=null;
  if(title!=null){trimmed=title.strip();if(trimmed.isEmpty()||trimmed.codePointCount(0,trimmed.length())>50)throw new BusinessException(ErrorCode.INVALID_INPUT);}
  return new UpdateScheduleCommand(trimmed,date);
 }
}
