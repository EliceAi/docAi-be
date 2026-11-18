package com.team.docai.web.analysis;

import com.team.docai.domain.analysis.service.query.AnalysisQueryService;
import com.team.docai.global.common.ApiResponse;
import com.team.docai.web.analysis.dto.response.AnalysisResultResponse;
import com.team.docai.web.analysis.dto.response.ClauseDetailResponse;
import com.team.docai.web.analysis.dto.response.SampleAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 분석 결과 조회, 조항 상세 조회, 예시 분석 조회. 셋 다 인증 필요 (명세 Authorization 필수).
 * /samples/{code} 는 글자 경로라 숫자 경로 /{analysisId} 보다 먼저 맞는다.
 */
@RestController
@RequestMapping("/api/analyses")
@RequiredArgsConstructor
public class AnalysisResultController {

    private final AnalysisQueryService analysisQueryService;

    /** 분석 결과 조회. 분석 중이면 진행 단계, 실패면 실패 정보, 완료면 결과 전체 */
    @GetMapping("/{analysisId}")
    public ResponseEntity<ApiResponse<AnalysisResultResponse>> getResult(
            @AuthenticationPrincipal UUID userId, @PathVariable Long analysisId) {
        return ResponseEntity.ok(ApiResponse.success(
                AnalysisResultResponse.from(analysisQueryService.getResult(userId, analysisId))));
    }

    /** 조항 상세 조회. 완료된 분석의 조항만 */
    @GetMapping("/{analysisId}/clauses/{clauseId}")
    public ResponseEntity<ApiResponse<ClauseDetailResponse>> getClause(
            @AuthenticationPrincipal UUID userId, @PathVariable Long analysisId, @PathVariable Long clauseId) {
        return ResponseEntity.ok(ApiResponse.success(
                ClauseDetailResponse.from(analysisQueryService.getClause(userId, analysisId, clauseId))));
    }

    /** 예시 분석 조회 (서버 고정 데이터) */
    @GetMapping("/samples/{documentTypeCode}")
    public ResponseEntity<ApiResponse<SampleAnalysisResponse>> getSample(@PathVariable String documentTypeCode) {
        return ResponseEntity.ok(ApiResponse.success(
                SampleAnalysisResponse.from(analysisQueryService.getSample(documentTypeCode))));
    }
}
