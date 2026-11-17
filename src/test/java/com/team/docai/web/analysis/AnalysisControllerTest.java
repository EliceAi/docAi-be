package com.team.docai.web.analysis;

import com.team.docai.domain.analysis.dto.AnalysisRequestInfo;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.service.AnalysisRequestService;
import com.team.docai.global.auth.AnonymousRateLimiter;
import com.team.docai.global.auth.TokenService;
import com.team.docai.global.config.SecurityConfig;
import com.team.docai.global.config.TokenConfig;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 요청 API 의 HTTP 모양 (경로, 인증, 상태 코드, 응답 필드, 에러 코드). 서비스는 가짜.
 * 인증은 실제 SecurityConfig·TokenConfig 로 발급한 토큰을 쓴다.
 */
@WebMvcTest(AnalysisController.class)
@Import({SecurityConfig.class, TokenConfig.class, TokenService.class, AnonymousRateLimiter.class})
class AnalysisControllerTest {

    @Autowired MockMvc mvc;
    @Autowired TokenService tokenService;
    @MockitoBean AnalysisRequestService analysisRequestService;

    final UUID userId = UUID.randomUUID();

    String bearer() {
        return "Bearer " + tokenService.issue(userId);
    }

    @Test
    void 접수되면_202와_분석_ID_진행_단계_읽기_진행을_준다() throws Exception {
        when(analysisRequestService.request(userId, 12L)).thenReturn(
                new AnalysisRequestInfo(88L, 12L, AnalysisStatus.ANALYZING, AnalysisStep.READING_TEXT, 0, 1));

        mvc.perform(post("/api/contracts/12/analyses").header("Authorization", bearer()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value(202))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.data.analysisId").value(88))
                .andExpect(jsonPath("$.data.contractId").value(12))
                .andExpect(jsonPath("$.data.status").value("ANALYZING"))
                .andExpect(jsonPath("$.data.progressStep").value("READING_TEXT"))
                .andExpect(jsonPath("$.data.readProgress.done").value(0))
                .andExpect(jsonPath("$.data.readProgress.total").value(1));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mvc.perform(post("/api/contracts/12/analyses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(analysisRequestService, never()).request(any(), any());
    }

    @Test
    void 토큰이_잘못되면_401() throws Exception {
        mvc.perform(post("/api/contracts/12/analyses").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 계약서가_없거나_남의_것이면_404_CONTRACT_NOT_FOUND() throws Exception {
        when(analysisRequestService.request(userId, 12L)).thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        mvc.perform(post("/api/contracts/12/analyses").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
    }

    @Test
    void 보관된_계약서면_409_CONTRACT_ARCHIVED() throws Exception {
        when(analysisRequestService.request(userId, 12L)).thenThrow(new BusinessException(ErrorCode.CONTRACT_ARCHIVED));

        mvc.perform(post("/api/contracts/12/analyses").header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_ARCHIVED"));
    }

    @Test
    void 요청이_너무_많으면_429_RATE_LIMITED() throws Exception {
        when(analysisRequestService.request(userId, 12L)).thenThrow(new BusinessException(ErrorCode.RATE_LIMITED));

        mvc.perform(post("/api/contracts/12/analyses").header("Authorization", bearer()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void 계약서_ID가_숫자가_아니면_400() throws Exception {
        mvc.perform(post("/api/contracts/abc/analyses").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
