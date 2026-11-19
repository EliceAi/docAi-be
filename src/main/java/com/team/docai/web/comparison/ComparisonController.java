package com.team.docai.web.comparison;
import com.team.docai.domain.comparison.service.ComparisonService;
import com.team.docai.global.common.ApiResponse;
import com.team.docai.global.error.*;
import com.team.docai.web.comparison.dto.request.*;
import com.team.docai.web.comparison.dto.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/api/comparisons") @RequiredArgsConstructor
public class ComparisonController {
 private final ComparisonService service;
 @GetMapping("/preview")
 public ApiResponse<ComparisonPreviewResponse> preview(@AuthenticationPrincipal UUID userId,@RequestParam Long previousContractId,@RequestParam Long revisedContractId){
  return ApiResponse.success(ComparisonPreviewResponse.from(service.preview(userId,previousContractId,revisedContractId)));
 }
 @PostMapping
 public ResponseEntity<ApiResponse<ComparisonResponse>> create(@AuthenticationPrincipal UUID userId,@RequestBody(required=false) CreateComparisonRequest request){
  if(request==null)throw new BusinessException(ErrorCode.INVALID_INPUT);
  return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(201,ComparisonResponse.from(service.create(userId,request.toCommand()))));
 }
 @GetMapping("/{comparisonId:[0-9]+}")
 public ApiResponse<ComparisonResponse> get(@AuthenticationPrincipal UUID userId,@PathVariable Long comparisonId){
  return ApiResponse.success(ComparisonResponse.from(service.get(userId,comparisonId)));
 }
 @PostMapping("/{comparisonId:[0-9]+}/manual-inputs")
 public ApiResponse<ComparisonResponse> manualInput(@AuthenticationPrincipal UUID userId,@PathVariable Long comparisonId,@RequestBody(required=false) ComparisonManualInputRequest request){
  if(request==null)throw new BusinessException(ErrorCode.INVALID_INPUT);
  return ApiResponse.success(ComparisonResponse.from(service.enterManualInput(userId,comparisonId,request.toCommand())));
 }
 @GetMapping("/{comparisonId:[0-9]+}/schedule-changes")
 public ApiResponse<ComparisonScheduleChangesResponse> scheduleChanges(@AuthenticationPrincipal UUID userId,@PathVariable Long comparisonId){
  return ApiResponse.success(ComparisonScheduleChangesResponse.from(service.getScheduleChanges(userId,comparisonId)));
 }
 @PostMapping("/{comparisonId:[0-9]+}/confirm")
 public ApiResponse<ConfirmComparisonResponse> confirm(@AuthenticationPrincipal UUID userId,@PathVariable Long comparisonId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody(required=false) ConfirmComparisonRequest request){
  return ApiResponse.success(ConfirmComparisonResponse.from(service.confirm(userId,comparisonId,key,request==null?new ConfirmComparisonRequest(null).toCommand():request.toCommand())));
 }
 @PostMapping("/{comparisonId:[0-9]+}/discard")
 public ApiResponse<Void> discard(@AuthenticationPrincipal UUID userId,@PathVariable Long comparisonId){
  service.discard(userId,comparisonId);
  return ApiResponse.success(null);
 }
}
