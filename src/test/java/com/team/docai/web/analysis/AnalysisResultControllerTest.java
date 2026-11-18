package com.team.docai.web.analysis;

import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.BasisType;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.entity.NumberComparison;
import com.team.docai.domain.analysis.service.query.AnalysisQueryService;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo;
import com.team.docai.domain.analysis.dto.ClauseDetailInfo;
import com.team.docai.domain.analysis.dto.SampleAnalysisInfo;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 결과·조항 상세·예시 분석 API 의 HTTP 모양 (경로, 인증, 상태 코드, 응답 필드, 명세 에러 코드 표의 행마다). 서비스는 가짜.
 */
@WebMvcTest(AnalysisResultController.class)
@Import({SecurityConfig.class, TokenConfig.class, TokenService.class, AnonymousRateLimiter.class})
class AnalysisResultControllerTest {

    @Autowired MockMvc mvc;
    @Autowired TokenService tokenService;
    @MockitoBean AnalysisQueryService service;

    final UUID userId = UUID.randomUUID();

    String bearer() {
        return "Bearer " + tokenService.issue(userId);
    }

    static AnalysisResultInfo.Result result() {
        return new AnalysisResultInfo.Result(
                "카페 알바 근로계약서", "EMPLOYMENT", "A", OffsetDateTime.parse("2026-11-12T14:20:00+09:00"),
                new AnalysisResultInfo.Coverage(2, List.of(1, 2), List.of(), 5),
                List.of(new AnalysisResultInfo.KeyTerm("임금", "시급 10,000원", KeyTermState.NEEDS_CHECK, KeyTermSource.WRITTEN),
                        new AnalysisResultInfo.KeyTerm("사업주", null, KeyTermState.MASKED, null)),
                new AnalysisResultInfo.Counts(2, 1, 1, 8),
                List.of(new AnalysisResultInfo.PriorityClause(704L, ClauseVerdict.NEEDS_CHECK,
                        "시급이 2026년 최저임금보다 낮아요", "시간당 320원 적어요.",
                        new NumberComparison("계약서 시급", "10,000원", "<", "2026년 최저임금", "10,320원"),
                        List.of(new AnalysisResultInfo.LawRef("최저임금법", "제6조")), BasisType.RULE,
                        List.of(new AnalysisResultInfo.RelatedClause(701L, "제1조", null)))),
                List.of(new AnalysisResultInfo.Finding("휴일", "주휴일을 꼭 적어야 해요", null,
                        List.of(new AnalysisResultInfo.LawRef("근로기준법", "제17조")))),
                List.of(new AnalysisResultInfo.Finding("신고", "30일 안에 신고", LocalDate.parse("2026-12-20"), List.of())),
                List.of(new AnalysisResultInfo.ClauseSummary(707L, "제7조", "교육비 반환", ClauseVerdict.NEEDS_CHECK, 2)),
                List.of("시급을 고쳐 주실 수 있나요?"),
                List.of(),
                4,
                new AnalysisResultInfo.SimilarContract(9L, "이전 알바", OffsetDateTime.parse("2026-10-01T09:00:00+09:00"), 3),
                AnalysisQueryService.DISCLAIMER);
    }

    static ClauseDetailInfo detail() {
        return new ClauseDetailInfo(707L, "제7조", "교육비 반환", ClauseVerdict.NEEDS_CHECK,
                "퇴사하면 미리 정한 금액을 물어내게 하는 조항",
                new ClauseDetailInfo.Original("근로자가 1년 이내 퇴사하면 교육비 30만 원을 반환한다.", 2, "2쪽 특약 첫 줄"),
                "1년이 되기 전에 그만두면 30만 원을 돌려줘야 해요.", "위약금 예정은 금지돼 있어요.",
                List.of(new ClauseDetailInfo.LawDetail("근로기준법", "제20조", "위약 예정의 금지", "사용자는 ...")),
                BasisType.AI_INTERPRETATION,
                List.of(new ClauseDetailInfo.RelatedClause(701L, "제1조", "계약 기간", "계약기간: 2026.12.01 ~ 2027.02.28")),
                List.of("교육비 반환 조항은 빼 주실 수 있나요?"),
                "1년 안에 그만두면 교육비 30만 원 반환");
    }

    // ---- 분석 결과 조회 ----

    @Test
    void 완료된_분석은_200과_결과_전체를_준다() throws Exception {
        when(service.getResult(userId, 88L)).thenReturn(new AnalysisResultInfo(88L, 12L, 1, AnalysisStatus.COMPLETED,
                AnalysisStep.DONE, 1, 1, null, result()));

        mvc.perform(get("/api/analyses/88").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.data.analysisId").value(88))
                .andExpect(jsonPath("$.data.contractId").value(12))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progressStep").value("DONE"))
                .andExpect(jsonPath("$.data.readProgress.done").value(1))
                .andExpect(jsonPath("$.data.readProgress.total").value(1))
                .andExpect(jsonPath("$.data.failure").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.result.title").value("카페 알바 근로계약서"))
                .andExpect(jsonPath("$.data.result.documentTypeCode").value("EMPLOYMENT"))
                .andExpect(jsonPath("$.data.result.checkLevel").value("A"))
                .andExpect(jsonPath("$.data.result.analyzedAt").value("2026-11-12T14:20:00+09:00"))
                .andExpect(jsonPath("$.data.result.coverage.pageCount").value(2))
                .andExpect(jsonPath("$.data.result.coverage.readPages[1]").value(2))
                .andExpect(jsonPath("$.data.result.coverage.partialPages").isEmpty())
                .andExpect(jsonPath("$.data.result.coverage.maskedCount").value(5))
                .andExpect(jsonPath("$.data.result.keyTerms[0].label").value("임금"))
                .andExpect(jsonPath("$.data.result.keyTerms[0].value").value("시급 10,000원"))
                .andExpect(jsonPath("$.data.result.keyTerms[0].state").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.data.result.keyTerms[0].source").value("WRITTEN"))
                .andExpect(jsonPath("$.data.result.keyTerms[1].state").value("MASKED"))
                .andExpect(jsonPath("$.data.result.keyTerms[1].value").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.result.keyTerms[1].source").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.result.counts.needsCheck").value(2))
                .andExpect(jsonPath("$.data.result.counts.missing").value(1))
                .andExpect(jsonPath("$.data.result.counts.deadline").value(1))
                .andExpect(jsonPath("$.data.result.counts.totalClauses").value(8))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].clauseId").value(704))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].verdict").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].title").value("시급이 2026년 최저임금보다 낮아요"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].description").value("시간당 320원 적어요."))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].comparison.leftLabel").value("계약서 시급"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].comparison.leftValue").value("10,000원"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].comparison.operator").value("<"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].comparison.rightLabel").value("2026년 최저임금"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].comparison.rightValue").value("10,320원"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].laws[0].lawName").value("최저임금법"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].laws[0].article").value("제6조"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].basisType").value("RULE"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].relatedClauses[0].clauseId").value(701))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].relatedClauses[0].articleNo").value("제1조"))
                .andExpect(jsonPath("$.data.result.priorityClauses[0].relatedClauses[0].title").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.result.missingItems[0].name").value("휴일"))
                .andExpect(jsonPath("$.data.result.missingItems[0].description").value("주휴일을 꼭 적어야 해요"))
                .andExpect(jsonPath("$.data.result.missingItems[0].dueDate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.result.missingItems[0].laws[0].article").value("제17조"))
                .andExpect(jsonPath("$.data.result.deadlines[0].dueDate").value("2026-12-20"))
                .andExpect(jsonPath("$.data.result.deadlines[0].laws").isEmpty())
                .andExpect(jsonPath("$.data.result.clauses[0].clauseId").value(707))
                .andExpect(jsonPath("$.data.result.clauses[0].articleNo").value("제7조"))
                .andExpect(jsonPath("$.data.result.clauses[0].title").value("교육비 반환"))
                .andExpect(jsonPath("$.data.result.clauses[0].verdict").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.data.result.clauses[0].pageNo").value(2))
                .andExpect(jsonPath("$.data.result.questionsBeforeSigning[0]").value("시급을 고쳐 주실 수 있나요?"))
                .andExpect(jsonPath("$.data.result.limitations").isEmpty())
                .andExpect(jsonPath("$.data.result.foundDateCount").value(4))
                .andExpect(jsonPath("$.data.result.similarContract.contractId").value(9))
                .andExpect(jsonPath("$.data.result.similarContract.title").value("이전 알바"))
                .andExpect(jsonPath("$.data.result.similarContract.analyzedAt").value("2026-10-01T09:00:00+09:00"))
                .andExpect(jsonPath("$.data.result.similarContract.scheduleCount").value(3))
                .andExpect(jsonPath("$.data.result.disclaimer").value(AnalysisQueryService.DISCLAIMER));
    }

    @Test
    void 분석_중이면_진행_단계만_주고_failure와_result는_null() throws Exception {
        when(service.getResult(userId, 88L)).thenReturn(new AnalysisResultInfo(88L, 12L, 1, AnalysisStatus.ANALYZING,
                AnalysisStep.MATCHING_LAWS, 1, 2, null, null));

        mvc.perform(get("/api/analyses/88").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANALYZING"))
                .andExpect(jsonPath("$.data.progressStep").value("MATCHING_LAWS"))
                .andExpect(jsonPath("$.data.readProgress.done").value(1))
                .andExpect(jsonPath("$.data.readProgress.total").value(2))
                .andExpect(jsonPath("$.data.failure").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.result").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 실패한_분석은_failure를_준다() throws Exception {
        when(service.getResult(userId, 88L)).thenReturn(new AnalysisResultInfo(88L, 12L, 1, AnalysisStatus.FAILED,
                AnalysisStep.READING_TEXT, 0, 1,
                new AnalysisResultInfo.Failure(AnalysisFailReason.TYPE_MISMATCH, null, "임대차계약서"), null));

        mvc.perform(get("/api/analyses/88").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failure.reason").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.data.failure.pageNo").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.failure.detectedTypeName").value("임대차계약서"))
                .andExpect(jsonPath("$.data.result").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 결과_조회_토큰이_없으면_401() throws Exception {
        mvc.perform(get("/api/analyses/88"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(service, never()).getResult(any(), any());
    }

    @Test
    void 결과_조회_토큰이_잘못되면_401() throws Exception {
        mvc.perform(get("/api/analyses/88").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 결과_조회_분석이_없거나_남의_것이면_404_ANALYSIS_NOT_FOUND() throws Exception {
        when(service.getResult(userId, 88L)).thenThrow(new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));

        mvc.perform(get("/api/analyses/88").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
    }

    @Test
    void 분석_ID가_숫자가_아니면_400() throws Exception {
        mvc.perform(get("/api/analyses/abc").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // ---- 조항 상세 조회 ----

    @Test
    void 조항_상세는_200과_모든_필드를_준다() throws Exception {
        when(service.getClause(userId, 88L, 707L)).thenReturn(detail());

        mvc.perform(get("/api/analyses/88/clauses/707").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.clauseId").value(707))
                .andExpect(jsonPath("$.data.articleNo").value("제7조"))
                .andExpect(jsonPath("$.data.title").value("교육비 반환"))
                .andExpect(jsonPath("$.data.verdict").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.data.issueSummary").value("퇴사하면 미리 정한 금액을 물어내게 하는 조항"))
                .andExpect(jsonPath("$.data.original.text").value("근로자가 1년 이내 퇴사하면 교육비 30만 원을 반환한다."))
                .andExpect(jsonPath("$.data.original.pageNo").value(2))
                .andExpect(jsonPath("$.data.original.location").value("2쪽 특약 첫 줄"))
                .andExpect(jsonPath("$.data.easyExplanation").value("1년이 되기 전에 그만두면 30만 원을 돌려줘야 해요."))
                .andExpect(jsonPath("$.data.whyCheck").value("위약금 예정은 금지돼 있어요."))
                .andExpect(jsonPath("$.data.laws[0].lawName").value("근로기준법"))
                .andExpect(jsonPath("$.data.laws[0].article").value("제20조"))
                .andExpect(jsonPath("$.data.laws[0].articleTitle").value("위약 예정의 금지"))
                .andExpect(jsonPath("$.data.laws[0].content").value("사용자는 ..."))
                .andExpect(jsonPath("$.data.basisType").value("AI_INTERPRETATION"))
                .andExpect(jsonPath("$.data.relatedClauses[0].clauseId").value(701))
                .andExpect(jsonPath("$.data.relatedClauses[0].articleNo").value("제1조"))
                .andExpect(jsonPath("$.data.relatedClauses[0].title").value("계약 기간"))
                .andExpect(jsonPath("$.data.relatedClauses[0].originalText").value("계약기간: 2026.12.01 ~ 2027.02.28"))
                .andExpect(jsonPath("$.data.askCounterpart[0]").value("교육비 반환 조항은 빼 주실 수 있나요?"))
                .andExpect(jsonPath("$.data.oneLine").value("1년 안에 그만두면 교육비 30만 원 반환"));
    }

    @Test
    void 조항_상세_토큰이_없으면_401() throws Exception {
        mvc.perform(get("/api/analyses/88/clauses/707"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(service, never()).getClause(any(), any(), any());
    }

    @Test
    void 조항_상세_분석이_없거나_남의_것이면_404_ANALYSIS_NOT_FOUND() throws Exception {
        when(service.getClause(userId, 88L, 707L)).thenThrow(new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));

        mvc.perform(get("/api/analyses/88/clauses/707").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
    }

    @Test
    void 조항_상세_해당_분석에_없는_조항이면_404_CLAUSE_NOT_FOUND() throws Exception {
        when(service.getClause(userId, 88L, 707L)).thenThrow(new BusinessException(ErrorCode.CLAUSE_NOT_FOUND));

        mvc.perform(get("/api/analyses/88/clauses/707").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("CLAUSE_NOT_FOUND"));
    }

    @Test
    void 조항_상세_분석이_끝나지_않았으면_409_ANALYSIS_NOT_COMPLETED() throws Exception {
        when(service.getClause(userId, 88L, 707L)).thenThrow(new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED));

        mvc.perform(get("/api/analyses/88/clauses/707").header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"));
    }

    // ---- 예시 분석 조회 ----

    @Test
    void 예시_분석은_200과_sample_true_result_clauseDetails를_준다() throws Exception {
        Map<Long, ClauseDetailInfo> details = new LinkedHashMap<>();
        details.put(707L, detail());
        when(service.getSample("EMPLOYMENT")).thenReturn(new SampleAnalysisInfo(result(), details));

        mvc.perform(get("/api/analyses/samples/EMPLOYMENT").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.analysisId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.contractId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.sample").value(true))
                .andExpect(jsonPath("$.data.result.title").value("카페 알바 근로계약서"))
                .andExpect(jsonPath("$.data.result.documentTypeCode").value("EMPLOYMENT"))
                .andExpect(jsonPath("$.data.result.counts.totalClauses").value(8))
                .andExpect(jsonPath("$.data.clauseDetails['707'].articleNo").value("제7조"))
                .andExpect(jsonPath("$.data.clauseDetails['707'].title").value("교육비 반환"))
                .andExpect(jsonPath("$.data.clauseDetails['707'].laws[0].article").value("제20조"));
    }

    @Test
    void 예시_분석_토큰이_없으면_401() throws Exception {
        mvc.perform(get("/api/analyses/samples/EMPLOYMENT"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(service, never()).getSample(anyString());
    }

    @Test
    void 예시가_없는_문서_종류면_404_SAMPLE_NOT_FOUND() throws Exception {
        when(service.getSample("LEASE")).thenThrow(new BusinessException(ErrorCode.SAMPLE_NOT_FOUND));

        mvc.perform(get("/api/analyses/samples/LEASE").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("SAMPLE_NOT_FOUND"));
    }
}
