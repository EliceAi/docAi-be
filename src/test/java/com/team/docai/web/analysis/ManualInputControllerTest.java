package com.team.docai.web.analysis;

import com.team.docai.domain.analysis.dto.ManualInputCommand;
import com.team.docai.domain.analysis.dto.ManualInputInfo;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.service.ManualInputService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 미인식 항목 입력 API 의 HTTP 모양 (경로, 인증, 요청 검증, 상태 코드, 응답 필드, 명세 에러 코드 표의 행마다). 서비스는 가짜.
 */
@WebMvcTest(ManualInputController.class)
@Import({SecurityConfig.class, TokenConfig.class, TokenService.class, AnonymousRateLimiter.class})
class ManualInputControllerTest {

    static final String URL = "/api/analyses/88/manual-inputs";
    static final String BODY = "{\"label\":\"휴일\",\"value\":\"매주 일요일\"}";

    @Autowired MockMvc mvc;
    @Autowired TokenService tokenService;
    @MockitoBean ManualInputService service;

    final UUID userId = UUID.randomUUID();

    ResultActions postJson(String body) throws Exception {
        return mvc.perform(post(URL).header("Authorization", "Bearer " + tokenService.issue(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 입력하면_200과_label_value_state_source를_준다() throws Exception {
        when(service.enter(userId, 88L, new ManualInputCommand("휴일", "매주 일요일")))
                .thenReturn(new ManualInputInfo("휴일", "매주 일요일", KeyTermState.UNREADABLE, KeyTermSource.MANUAL));

        postJson(BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.data.label").value("휴일"))
                .andExpect(jsonPath("$.data.value").value("매주 일요일"))
                .andExpect(jsonPath("$.data.state").value("UNREADABLE"))
                .andExpect(jsonPath("$.data.source").value("MANUAL"));
    }

    @Test
    void 앞뒤_공백은_지우고_서비스에_넘긴다() throws Exception {
        when(service.enter(userId, 88L, new ManualInputCommand("휴일", "매주 일요일")))
                .thenReturn(new ManualInputInfo("휴일", "매주 일요일", KeyTermState.UNREADABLE, KeyTermSource.MANUAL));

        postJson("{\"label\":\" 휴일 \",\"value\":\"  매주 일요일 \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value("매주 일요일"));
    }

    @Test
    void 토큰이_없으면_401_UNAUTHORIZED() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(service, never()).enter(any(), any(), any());
    }

    @Test
    void 토큰이_잘못되면_401_UNAUTHORIZED() throws Exception {
        mvc.perform(post(URL).header("Authorization", "Bearer not-a-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(service, never()).enter(any(), any(), any());
    }

    @Test
    void 필수값이_없거나_형식이_틀리면_400_INVALID_INPUT() throws Exception {
        String over = "가".repeat(101);
        String[] bodies = {
                "{\"value\":\"매주 일요일\"}",              // label 없음
                "{\"label\":\"  \",\"value\":\"매주 일요일\"}", // label 공백
                "{\"label\":\"휴일\"}",                     // value 없음
                "{\"label\":\"휴일\",\"value\":\"\"}",       // value 빈 값
                "{\"label\":\"휴일\",\"value\":\"" + over + "\"}", // 100자 초과
                "{\"label\":\"휴일\",",                     // 깨진 JSON
                ""                                          // 본문 없음
        };
        for (String body : bodies) {
            postJson(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verify(service, never()).enter(any(), any(), any());
    }

    @Test
    void 앞뒤_공백을_지우면_비는_값과_코드_포인트로_100자를_넘는_값은_400_INVALID_INPUT() throws Exception {
        String emoji101 = "😀".repeat(101);
        String[] bodies = {
                "{\"label\":\"휴일\",\"value\":\"\\u3000\\u3000\"}",     // 전각 공백만 (strip 후 빔)
                "{\"label\":\"\\u2003\",\"value\":\"매주 일요일\"}",     // label 유니코드 공백만
                "{\"label\":\"휴일\",\"value\":\"" + emoji101 + "\"}"    // 이모지 101자 (UTF-16 202)
        };
        for (String body : bodies) {
            postJson(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verify(service, never()).enter(any(), any(), any());
    }

    @Test
    void 이모지_100자와_앞뒤_공백을_지워_100자인_값은_통과한다() throws Exception {
        String emoji100 = "😀".repeat(100);
        when(service.enter(userId, 88L, new ManualInputCommand("휴일", emoji100)))
                .thenReturn(new ManualInputInfo("휴일", emoji100, KeyTermState.UNREADABLE, KeyTermSource.MANUAL));
        String exact = "가".repeat(100);
        when(service.enter(userId, 88L, new ManualInputCommand("휴일", exact)))
                .thenReturn(new ManualInputInfo("휴일", exact, KeyTermState.UNREADABLE, KeyTermSource.MANUAL));

        postJson("{\"label\":\"휴일\",\"value\":\"" + emoji100 + "\"}").andExpect(status().isOk());
        postJson("{\"label\":\"휴일\",\"value\":\"  " + exact + "  \"}").andExpect(status().isOk());
    }

    @Test
    void 값이_정확히_100자면_통과한다() throws Exception {
        String exact = "가".repeat(100);
        when(service.enter(userId, 88L, new ManualInputCommand("휴일", exact)))
                .thenReturn(new ManualInputInfo("휴일", exact, KeyTermState.UNREADABLE, KeyTermSource.MANUAL));

        postJson("{\"label\":\"휴일\",\"value\":\"" + exact + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value(exact));
    }

    @Test
    void 분석_ID가_숫자가_아니면_400_INVALID_INPUT() throws Exception {
        mvc.perform(post("/api/analyses/abc/manual-inputs").header("Authorization", "Bearer " + tokenService.issue(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void Content_Type이_JSON이_아니면_400_INVALID_INPUT() throws Exception {
        mvc.perform(post(URL).header("Authorization", "Bearer " + tokenService.issue(userId))
                        .contentType(MediaType.TEXT_PLAIN).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 분석이_없거나_남의_것이면_404_ANALYSIS_NOT_FOUND() throws Exception {
        when(service.enter(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));

        postJson(BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
    }

    @Test
    void 없는_항목이면_404_TERM_NOT_FOUND() throws Exception {
        when(service.enter(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.TERM_NOT_FOUND));

        postJson(BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("TERM_NOT_FOUND"));
    }

    @Test
    void 읽지_못한_칸이_아니면_409_TERM_NOT_EDITABLE() throws Exception {
        when(service.enter(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.TERM_NOT_EDITABLE));

        postJson(BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("TERM_NOT_EDITABLE"));
    }

    @Test
    void 분석이_끝나지_않았으면_409_ANALYSIS_NOT_COMPLETED() throws Exception {
        when(service.enter(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED));

        postJson(BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"));
    }
}
