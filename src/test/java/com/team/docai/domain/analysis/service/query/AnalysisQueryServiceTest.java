package com.team.docai.domain.analysis.service.query;

import com.team.docai.domain.analysis.dto.AnalysisResultInfo;
import com.team.docai.domain.analysis.dto.ClauseDetailInfo;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.BasisType;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.repository.AnalysisChunkRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRelationRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.repository.AnalysisResultReader;
import com.team.docai.domain.contract.dto.ContractVersionInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.law.service.LawSearchService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UNCERTAIN 조항은 쉬운 설명과 물어볼 것만 보여 주고 법적 판단 문구(why_check, issue_summary)와 법령은 보여 주지 않는다
 * (분석 결과 조회 명세 비고). DB 없이 저장소를 흉내 내 확인한다.
 */
class AnalysisQueryServiceTest {

    static final UUID USER = UUID.randomUUID();
    static final long ANALYSIS_ID = 10L;
    static final long VERSION_ID = 20L;

    AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    AnalysisChunkRepository chunkRepository = mock(AnalysisChunkRepository.class);
    AnalysisKeyTermRepository keyTermRepository = mock(AnalysisKeyTermRepository.class);
    AnalysisClauseRepository clauseRepository = mock(AnalysisClauseRepository.class);
    AnalysisClauseRefRepository refRepository = mock(AnalysisClauseRefRepository.class);
    AnalysisClauseRelationRepository relationRepository = mock(AnalysisClauseRelationRepository.class);
    AnalysisResultReader reader = mock(AnalysisResultReader.class);
    ContractService contractService = mock(ContractService.class);
    ContractReadOnlyQuery contractQuery = mock(ContractReadOnlyQuery.class);
    LawSearchService lawSearchService = mock(LawSearchService.class);
    SampleAnalysisReader sampleReader = mock(SampleAnalysisReader.class);

    AnalysisQueryService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisQueryService(analysisRepository, chunkRepository, keyTermRepository, clauseRepository,
                refRepository, relationRepository, reader, contractService, contractQuery, lawSearchService, sampleReader,
                Clock.system(ZoneId.of("Asia/Seoul")));
        Analysis analysis = mock(Analysis.class);
        when(analysis.getAnalysisId()).thenReturn(ANALYSIS_ID);
        when(analysis.getVersionId()).thenReturn(VERSION_ID);
        when(analysis.getStatus()).thenReturn(AnalysisStatus.COMPLETED);
        when(analysis.getDocumentTypeCode()).thenReturn("EMPLOYMENT");
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.of(analysis));
        when(contractService.getVersionOwner(USER, VERSION_ID))
                .thenReturn(new ContractVersionInfo(1L, 1, 2, "근로계약서", 0));
        when(contractQuery.findSimilarContract(any(), anyLong())).thenReturn(Optional.empty());
        when(chunkRepository.findByAnalysisIdOrderByChunkNoAsc(ANALYSIS_ID)).thenReturn(List.of());
        when(keyTermRepository.findByAnalysisIdOrderBySortOrderAsc(ANALYSIS_ID)).thenReturn(List.of());
        when(reader.findCitedLawRefs(anyCollection())).thenReturn(List.of());
        when(reader.findRelations(anyCollection())).thenReturn(List.of());
        when(reader.findFindings(ANALYSIS_ID)).thenReturn(List.of());
        when(reader.findVisibleClauses(anyLong(), anyCollection())).thenReturn(List.of());
        when(relationRepository.findByClauseId(anyLong())).thenReturn(List.of());
        when(refRepository.findByClauseIdOrderByRefIdAsc(anyLong())).thenReturn(List.of());
    }

    @Test
    void 결과_조회는_B_getVersionOwner_의_계약서_ID_버전_번호_제목_쪽_수_가린_곳_수를_쓴다() {
        when(contractService.getVersionOwner(USER, VERSION_ID))
                .thenReturn(new ContractVersionInfo(31L, 3, 5, "카페 근로계약서", 4));
        when(clauseRepository.findByAnalysisIdAndExcludedFalseAndVerdictNotOrderByPageNoAscClauseIdAsc(ANALYSIS_ID,
                com.team.docai.domain.analysis.entity.ClauseVerdict.JUDGING)).thenReturn(List.of());

        var info = service.getResult(USER, ANALYSIS_ID);

        assertThat(info.contractId()).isEqualTo(31L);
        assertThat(info.version()).isEqualTo(3);
        assertThat(info.result().title()).isEqualTo("카페 근로계약서");
        assertThat(info.result().coverage().pageCount()).isEqualTo(5);
        assertThat(info.result().coverage().maskedCount()).isEqualTo(4);
        verify(contractService).getVersionOwner(USER, VERSION_ID);
    }

    @Test
    void 결과_조회와_조항_상세는_B가_CONTRACT_NOT_FOUND면_ANALYSIS_NOT_FOUND() {
        when(contractService.getVersionOwner(USER, VERSION_ID))
                .thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        assertThatThrownBy(() -> service.getResult(USER, ANALYSIS_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_FOUND));
        assertThatThrownBy(() -> service.getClause(USER, ANALYSIS_ID, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_FOUND));
        verify(chunkRepository, never()).findByAnalysisIdOrderByChunkNoAsc(anyLong());
        verify(reader, never()).findVisibleClause(anyLong(), anyLong());
    }

    @Test
    void 결과_조회는_분석이_없으면_B를_부르지_않고_ANALYSIS_NOT_FOUND() {
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getResult(USER, ANALYSIS_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_FOUND));
        verify(contractService, never()).getVersionOwner(any(), anyLong());
    }

    @Test
    void 결과_조회에서_UNCERTAIN_조항은_description이_null이고_NEEDS_CHECK는_그대로() {
        AnalysisClause uncertain = clause(1L, ClauseVerdict.UNCERTAIN, 1);
        AnalysisClause needsCheck = clause(2L, ClauseVerdict.NEEDS_CHECK, 2);
        when(clauseRepository.findByAnalysisIdAndExcludedFalseAndVerdictNotOrderByPageNoAscClauseIdAsc(
                ANALYSIS_ID, ClauseVerdict.JUDGING)).thenReturn(List.of(uncertain, needsCheck));

        List<AnalysisResultInfo.PriorityClause> priority =
                service.getResult(USER, ANALYSIS_ID).result().priorityClauses();

        assertThat(priority).extracting(AnalysisResultInfo.PriorityClause::clauseId).containsExactly(1L, 2L);
        assertThat(priority.get(0).title()).isEqualTo("제목 1");
        assertThat(priority.get(0).description()).isNull();
        assertThat(priority.get(0).laws()).isEmpty();
        assertThat(priority.get(1).description()).isEqualTo("이유 2");
    }

    @Test
    void 조항_상세에서_UNCERTAIN_조항은_쉬운_설명과_물어볼_것만_주고_판단_문구와_법령은_숨긴다() {
        AnalysisClause uncertain = clause(1L, ClauseVerdict.UNCERTAIN, 1);
        when(reader.findVisibleClause(ANALYSIS_ID, 1L)).thenReturn(Optional.of(uncertain));

        ClauseDetailInfo info = service.getClause(USER, ANALYSIS_ID, 1L);

        assertThat(info.verdict()).isEqualTo(ClauseVerdict.UNCERTAIN);
        assertThat(info.easyExplanation()).isEqualTo("쉬운 설명 1");
        assertThat(info.askCounterpart()).containsExactly("물어볼 것 1");
        assertThat(info.issueSummary()).isNull();
        assertThat(info.whyCheck()).isNull();
        assertThat(info.laws()).isEmpty();
        verify(refRepository, never()).findByClauseIdOrderByRefIdAsc(anyLong());
    }

    @Test
    void 조항_상세에서_NEEDS_CHECK_조항은_판단_문구를_준다() {
        AnalysisClause needsCheck = clause(2L, ClauseVerdict.NEEDS_CHECK, 1);
        when(reader.findVisibleClause(ANALYSIS_ID, 2L)).thenReturn(Optional.of(needsCheck));

        ClauseDetailInfo info = service.getClause(USER, ANALYSIS_ID, 2L);

        assertThat(info.issueSummary()).isEqualTo("요약 2");
        assertThat(info.whyCheck()).isEqualTo("이유 2");
    }

    @Test
    void 조항_상세에서_2차_판단을_거치지_않은_OK_조항은_쉬운_설명을_한_줄_요약으로_basisType을_AI_INTERPRETATION으로_채운다() {
        AnalysisClause ok = okWithoutJudgment(3L, "한 줄 요약 3");
        when(reader.findVisibleClause(ANALYSIS_ID, 3L)).thenReturn(Optional.of(ok));

        ClauseDetailInfo info = service.getClause(USER, ANALYSIS_ID, 3L);

        assertThat(info.verdict()).isEqualTo(ClauseVerdict.OK);
        assertThat(info.easyExplanation()).isEqualTo("한 줄 요약 3");
        assertThat(info.oneLine()).isEqualTo("한 줄 요약 3");
        assertThat(info.basisType()).isEqualTo(BasisType.AI_INTERPRETATION);
        assertThat(info.issueSummary()).isNull();
        assertThat(info.whyCheck()).isNull();
    }

    @Test
    void 조항_상세에서_OK_조항의_한_줄_요약도_없으면_쉬운_설명은_null이고_basisType은_채운다() {
        AnalysisClause ok = okWithoutJudgment(4L, null);
        when(reader.findVisibleClause(ANALYSIS_ID, 4L)).thenReturn(Optional.of(ok));

        ClauseDetailInfo info = service.getClause(USER, ANALYSIS_ID, 4L);

        assertThat(info.easyExplanation()).isNull();
        assertThat(info.oneLine()).isNull();
        assertThat(info.basisType()).isEqualTo(BasisType.AI_INTERPRETATION);
    }

    @Test
    void 조항_상세에서_저장된_쉬운_설명과_basisType은_그대로_쓴다() {
        AnalysisClause needsCheck = clause(5L, ClauseVerdict.NEEDS_CHECK, 1);
        when(needsCheck.getOneLine()).thenReturn("한 줄 요약 5");
        when(needsCheck.getBasisType()).thenReturn(BasisType.RULE);
        when(reader.findVisibleClause(ANALYSIS_ID, 5L)).thenReturn(Optional.of(needsCheck));

        ClauseDetailInfo info = service.getClause(USER, ANALYSIS_ID, 5L);

        assertThat(info.easyExplanation()).isEqualTo("쉬운 설명 5");
        assertThat(info.basisType()).isEqualTo(BasisType.RULE);
    }

    @Test
    void 결과_조회의_priorityClauses_basisType이_비어_있으면_AI_INTERPRETATION() {
        AnalysisClause noBasis = clause(6L, ClauseVerdict.NEEDS_CHECK, 1);
        AnalysisClause rule = clause(7L, ClauseVerdict.NEEDS_CHECK, 2);
        when(rule.getBasisType()).thenReturn(BasisType.RULE);
        when(clauseRepository.findByAnalysisIdAndExcludedFalseAndVerdictNotOrderByPageNoAscClauseIdAsc(
                ANALYSIS_ID, ClauseVerdict.JUDGING)).thenReturn(List.of(noBasis, rule));

        List<AnalysisResultInfo.PriorityClause> priority =
                service.getResult(USER, ANALYSIS_ID).result().priorityClauses();

        assertThat(priority).extracting(AnalysisResultInfo.PriorityClause::basisType)
                .containsExactly(BasisType.AI_INTERPRETATION, BasisType.RULE);
    }

    /** 의심 표시 없이 9단계에서 OK 가 된 조항: easy_explanation·basis_type·why_check·issue_summary 가 비어 있다 */
    private static AnalysisClause okWithoutJudgment(long id, String oneLine) {
        AnalysisClause c = mock(AnalysisClause.class);
        when(c.getClauseId()).thenReturn(id);
        when(c.getVerdict()).thenReturn(ClauseVerdict.OK);
        when(c.getPageNo()).thenReturn(1);
        when(c.getOriginalText()).thenReturn("원문 " + id);
        when(c.getOneLine()).thenReturn(oneLine);
        return c;
    }

    private static AnalysisClause clause(long id, ClauseVerdict verdict, int priority) {
        AnalysisClause c = mock(AnalysisClause.class);
        when(c.getClauseId()).thenReturn(id);
        when(c.getVerdict()).thenReturn(verdict);
        when(c.getPriority()).thenReturn(priority);
        when(c.getPageNo()).thenReturn(1);
        when(c.getArticleNo()).thenReturn("제" + id + "조");
        when(c.getHeadline()).thenReturn("제목 " + id);
        when(c.getWhyCheck()).thenReturn("이유 " + id);
        when(c.getIssueSummary()).thenReturn("요약 " + id);
        when(c.getEasyExplanation()).thenReturn("쉬운 설명 " + id);
        when(c.getAskCounterpartJson()).thenReturn(List.of("물어볼 것 " + id));
        when(c.getOriginalText()).thenReturn("원문 " + id);
        return c;
    }
}
