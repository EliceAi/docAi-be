package com.team.docai.web.analysis;

import com.team.docai.domain.analysis.service.ManualInputService;
import com.team.docai.global.common.ApiResponse;
import com.team.docai.web.analysis.dto.request.ManualInputRequest;
import com.team.docai.web.analysis.dto.response.ManualInputResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 미인식 항목 입력. 인증 필요 (명세 Authorization 필수). 새 자원을 만들지 않고 칸 값을 채우므로 200 */
@RestController
@RequestMapping("/api/analyses")
@RequiredArgsConstructor
public class ManualInputController {

    private final ManualInputService manualInputService;

    @PostMapping("/{analysisId}/manual-inputs")
    public ResponseEntity<ApiResponse<ManualInputResponse>> enter(
            @AuthenticationPrincipal UUID userId, @PathVariable Long analysisId,
            @Valid @RequestBody ManualInputRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                ManualInputResponse.from(manualInputService.enter(userId, analysisId, request.toCommand()))));
    }
}
