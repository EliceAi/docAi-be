package com.team.docai.web.analysis;

import com.team.docai.domain.analysis.dto.AnalysisRequestInfo;
import com.team.docai.domain.analysis.service.AnalysisRequestService;
import com.team.docai.global.common.ApiResponse;
import com.team.docai.web.analysis.dto.response.AnalysisRequestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisRequestService analysisRequestService;

    /** 분석 요청. 접수만 하고 202 (분석은 백그라운드). 이미 분석 중이면 그 분석을 202 로 돌려준다. */
    @PostMapping("/contracts/{contractId}/analyses")
    public ResponseEntity<ApiResponse<AnalysisRequestResponse>> request(
            @AuthenticationPrincipal UUID userId, @PathVariable Long contractId) {
        AnalysisRequestInfo info = analysisRequestService.request(userId, contractId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(HttpStatus.ACCEPTED.value(), AnalysisRequestResponse.from(info)));
    }
}
