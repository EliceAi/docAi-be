package com.team.docai.domain.analysis.service.query;

import com.team.docai.domain.analysis.dto.AnalysisResultInfo;
import com.team.docai.domain.analysis.dto.ClauseDetailInfo;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.repository.AnalysisChunkRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRelationRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.repository.AnalysisResultReader;
import com.team.docai.domain.analysis.service.query.ContractReadOnlyQuery.OwnedVersion;
import com.team.docai.domain.law.service.LawSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    ContractReadOnlyQuery contractQuery = mock(ContractReadOnlyQuery.class);
    LawSearchService lawSearchService = mock(LawSearchService.class);
    SampleAnalysisReader sampleReader = mock(SampleAnalysisReader.class);

    AnalysisQueryService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisQueryService(analysisRepository, chunkRepository, keyTermRepository, clauseRepository,
                refRepository, relationRepository, reader, contractQuery, lawSearchService, sampleReader,
                Clock.system(ZoneId.of("Asia/Seoul")));
        Analysis analysis = mock(Analysis.class);
        when(analysis.getAnalysisId()).thenReturn(ANALYSIS_ID);
        when(analysis.getVersionId()).thenReturn(VERSION_ID);
        when(analysis.getStatus()).thenReturn(AnalysisStatus.COMPLETED);
        when(analysis.getDocumentTypeCode()).thenReturn("EMPLOYMENT");
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.of(analysis));
        when(contractQuery.findOwnedVersion(USER, VERSION_ID))
                .thenReturn(Optional.of(new OwnedVersion(1L, 1, 2, "근로계약서", 0)));
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
