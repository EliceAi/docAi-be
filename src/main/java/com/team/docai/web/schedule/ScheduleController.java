package com.team.docai.web.schedule;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.web.schedule.dto.response.UpcomingScheduleResponse;
import com.team.docai.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.*;
@RestController @RequestMapping("/api/schedules") @RequiredArgsConstructor
public class ScheduleController {
 private final ScheduleService service;
 @GetMapping({"","/upcoming"}) public ApiResponse<List<UpcomingScheduleResponse>> upcoming(@AuthenticationPrincipal UUID userId,@RequestParam(defaultValue="2") int limit){
  return ApiResponse.success(service.upcoming(userId,limit).stream().map(UpcomingScheduleResponse::from).toList());
 }
 @DeleteMapping("/{scheduleId:[0-9]+}") public ApiResponse<Void> delete(@AuthenticationPrincipal UUID userId,@PathVariable Long scheduleId){service.delete(userId,scheduleId);return ApiResponse.success(null);}
}
