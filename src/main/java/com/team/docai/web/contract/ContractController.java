package com.team.docai.web.contract;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.web.contract.dto.request.*;
import com.team.docai.web.contract.dto.response.*;
import com.team.docai.global.common.ApiResponse;
import com.team.docai.global.error.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import java.util.UUID;
@RestController @RequestMapping("/api/contracts") @RequiredArgsConstructor
public class ContractController {
 private final ContractService contracts;private final ObjectMapper mapper;
 @GetMapping
 public ApiResponse<MyContractsResponse> list(@AuthenticationPrincipal UUID userId,@RequestParam(required=false) String category,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
  return ApiResponse.success(MyContractsResponse.from(contracts.listMyContracts(userId,category,page,size)));
 }
 @GetMapping("/{contractId:[0-9]+}")
 public ApiResponse<MyContractDetailResponse> detail(@AuthenticationPrincipal UUID userId,@PathVariable Long contractId){
  return ApiResponse.success(MyContractDetailResponse.from(contracts.getMyContractDetail(userId,contractId)));
 }
 @PostMapping("/{contractId:[0-9]+}/archive")
 public ApiResponse<ArchiveContractResponse> archive(@AuthenticationPrincipal UUID userId,@PathVariable Long contractId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody(required=false) ArchiveContractRequest request){
  return ApiResponse.success(ArchiveContractResponse.from(contracts.archive(userId,contractId,key,request==null?new ArchiveContractRequest(null).toCommand():request.toCommand())));
 }
 @PatchMapping("/{contractId:[0-9]+}")
 public ApiResponse<ChangeDocumentTypeResponse> changeDocumentType(@AuthenticationPrincipal UUID userId,@PathVariable Long contractId,@Valid @RequestBody ChangeDocumentTypeRequest request){
  return ApiResponse.success(ChangeDocumentTypeResponse.from(contracts.changeDocumentType(userId,contractId,request.documentTypeCode())));
 }
 @PutMapping(value="/{contractId:[0-9]+}/pages/{pageNo:[0-9]+}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 public ApiResponse<ReuploadPageResponse> reuploadPage(@AuthenticationPrincipal UUID userId,@PathVariable Long contractId,@PathVariable int pageNo,@RequestParam("page") MultipartFile page,@RequestParam(value="maskedCount",defaultValue="0") int maskedCount){
  if(page==null||page.isEmpty())throw new BusinessException(ErrorCode.INVALID_INPUT);
  if(page.getSize()>20L*1024*1024)throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
  try{return ApiResponse.success(ReuploadPageResponse.from(contracts.reuploadPage(userId,contractId,pageNo,page.getBytes(),maskedCount)));}
  catch(IOException e){throw new BusinessException(ErrorCode.INVALID_INPUT);}
 }
 @GetMapping(value="/{contractId}/pages/{pageNo}",produces=MediaType.IMAGE_JPEG_VALUE)
 public ResponseEntity<byte[]> page(@AuthenticationPrincipal UUID userId,@PathVariable Long contractId,@PathVariable int pageNo,@RequestParam(required=false) Integer version){
  return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(org.springframework.http.CacheControl.noStore()).body(contracts.readPage(userId,contractId,pageNo,version));
 }
 @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 public ResponseEntity<ApiResponse<CreateContractResponse>> create(@AuthenticationPrincipal UUID userId,@RequestHeader("Idempotency-Key") UUID key,@ModelAttribute CreateContractRequest request){
  return ResponseEntity.status(201).body(ApiResponse.success(201,CreateContractResponse.from(contracts.create(userId,key,request.toCommand(mapper)))));
 }
}
