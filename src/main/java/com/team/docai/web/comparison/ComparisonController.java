package com.team.docai.web.comparison;

import com.team.docai.domain.comparison.service.ComparisonService;
import com.team.docai.web.comparison.dto.response.ComparisonResponse;
import com.team.docai.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.UUID;
@RestController @RequestMapping("/api/comparisons") @RequiredArgsConstructor
public class ComparisonController {private final ComparisonService service;
    @PostMapping("/inputs") public ApiResponse<ComparisonResponse> input(@AuthenticationPrincipal UUID user,@jakarta.validation.Valid @RequestBody com.team.docai.web.comparison.dto.request.ComparisonInputRequest r){return ApiResponse.success(ComparisonResponse.from(service.input(user,r.toCommand())));}
    @GetMapping public ApiResponse<ComparisonResponse> compare(@AuthenticationPrincipal UUID user,@RequestParam UUID previousId,@RequestParam UUID currentId){return ApiResponse.success(ComparisonResponse.from(service.compare(user,previousId,currentId)));}
}
