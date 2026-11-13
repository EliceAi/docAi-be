package com.team.docai.web.schedule;

import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.web.schedule.dto.request.ArchiveRequest;
import com.team.docai.web.schedule.dto.response.ScheduleResponse;
import com.team.docai.web.document.dto.response.CheckResponse;
import com.team.docai.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.*;
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class ScheduleController {
    private final ScheduleService service;
    @GetMapping("/schedules") public ApiResponse<List<ScheduleResponse>> list(@AuthenticationPrincipal UUID user){return ApiResponse.success(service.list(user).stream().map(ScheduleResponse::from).toList());}
    @PostMapping("/checks/{id}/archives") public ApiResponse<CheckResponse> archive(@AuthenticationPrincipal UUID user,@PathVariable UUID id,@Valid @RequestBody ArchiveRequest r){return ApiResponse.success(CheckResponse.from(service.archive(user,id,r.toCommand())));}
}
