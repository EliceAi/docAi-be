package com.team.docai.web.contract.dto.request;
import java.util.*;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.team.docai.domain.contract.dto.CreateContractCommand;
import com.team.docai.global.error.*;
public record CreateContractRequest(String documentTypeCode,List<MultipartFile> pages,String maskedCounts){
 public CreateContractCommand toCommand(ObjectMapper mapper){
  if(documentTypeCode==null||documentTypeCode.isBlank())throw new BusinessException(ErrorCode.INVALID_INPUT);
  if(pages==null||pages.isEmpty()||pages.size()>30)throw new BusinessException(ErrorCode.INVALID_FILE);
  if(pages.stream().anyMatch(p->p.getSize()>20L*1024*1024)||pages.stream().mapToLong(MultipartFile::getSize).sum()>100L*1024*1024)throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
  try{
   List<Integer> counts;
   if(maskedCounts==null)counts=Collections.nCopies(pages.size(),0);
   else {var node=mapper.readTree(maskedCounts);if(node==null||!node.isArray())throw new BusinessException(ErrorCode.INVALID_INPUT);counts=new ArrayList<>();for(var n:node){if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<0)throw new BusinessException(ErrorCode.INVALID_INPUT);counts.add(n.intValue());}}
   if(counts.size()!=pages.size())throw new BusinessException(ErrorCode.INVALID_INPUT);
   List<byte[]> images=new ArrayList<>();for(var page:pages)images.add(page.getBytes());
   return new CreateContractCommand(documentTypeCode,images,counts);
  }catch(IOException e){throw new BusinessException(ErrorCode.INVALID_INPUT);}
 }
}
