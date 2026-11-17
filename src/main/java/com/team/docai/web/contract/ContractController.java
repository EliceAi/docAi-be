package com.team.docai.web.contract;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.web.contract.dto.request.CreateContractRequest;
import com.team.docai.web.contract.dto.response.CreateContractResponse;
import com.team.docai.global.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
@RestController @RequestMapping("/api/contracts") @RequiredArgsConstructor
public class ContractController {
 private final ContractService contracts;private final ObjectMapper mapper;
 @GetMapping(value="/{contractId}/pages/{pageNo}",produces=MediaType.IMAGE_JPEG_VALUE)
 public ResponseEntity<byte[]> page(@AuthenticationPrincipal UUID userId,@PathVariable Long contractId,@PathVariable int pageNo,@RequestParam(required=false) Integer version){
  return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(org.springframework.http.CacheControl.noStore()).body(contracts.readPage(userId,contractId,pageNo,version));
 }
 @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 public ResponseEntity<ApiResponse<CreateContractResponse>> create(@AuthenticationPrincipal UUID userId,@RequestHeader("Idempotency-Key") UUID key,@ModelAttribute CreateContractRequest request){
  return ResponseEntity.status(201).body(ApiResponse.success(201,CreateContractResponse.from(contracts.create(userId,key,request.toCommand(mapper)))));
 }
}
