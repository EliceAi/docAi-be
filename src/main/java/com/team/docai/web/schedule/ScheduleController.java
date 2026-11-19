package com.team.docai.web.schedule;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.web.schedule.dto.request.UpdateScheduleRequest;
import com.team.docai.web.schedule.dto.response.*;
import com.team.docai.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.*;
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class ScheduleController {
 private final ScheduleService service;
 @GetMapping({"/schedules","/schedules/upcoming"}) public ApiResponse<List<UpcomingScheduleResponse>> upcoming(@AuthenticationPrincipal UUID userId,@RequestParam(defaultValue="2") int limit){
  return ApiResponse.success(service.upcoming(userId,limit).stream().map(UpcomingScheduleResponse::from).toList());
 }
 @GetMapping("/schedules/{scheduleId:[0-9]+}") public ApiResponse<ScheduleDetailResponse> detail(@AuthenticationPrincipal UUID userId,@PathVariable Long scheduleId){
  return ApiResponse.success(ScheduleDetailResponse.from(service.getDetail(userId,scheduleId)));
 }
 @PatchMapping("/schedules/{scheduleId:[0-9]+}") public ApiResponse<UpdateScheduleResponse> update(@AuthenticationPrincipal UUID userId,@PathVariable Long scheduleId,@RequestBody(required=false) UpdateScheduleRequest request){
  if(request==null)throw new com.team.docai.global.error.BusinessException(com.team.docai.global.error.ErrorCode.INVALID_INPUT);
  return ApiResponse.success(UpdateScheduleResponse.from(service.update(userId,scheduleId,request.toCommand())));
 }
 @DeleteMapping("/schedules/{scheduleId:[0-9]+}") public ApiResponse<Void> delete(@AuthenticationPrincipal UUID userId,@PathVariable Long scheduleId){service.delete(userId,scheduleId);return ApiResponse.success(null);}
 @GetMapping("/analyses/{analysisId:[0-9]+}/schedule-candidates") public ApiResponse<ScheduleCandidateSetResponse> candidates(@AuthenticationPrincipal UUID userId,@PathVariable Long analysisId){
  return ApiResponse.success(ScheduleCandidateSetResponse.from(service.getCandidates(userId,analysisId)));
 }
}
