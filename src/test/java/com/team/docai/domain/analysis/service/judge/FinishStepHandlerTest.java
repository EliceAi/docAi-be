package com.team.docai.domain.analysis.service.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.ScheduleCandidate;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 완료 전 정리: 일정 후보 저장 (이름 규칙은 AnalysisTitleTest) */
class FinishStepHandlerTest {

    static final AnalysisRunContext CTX = new AnalysisRunContext(88L, UUID.randomUUID(), 12L, 30L, "EMPLOYMENT");

    final ObjectMapper mapper = new ObjectMapper();
    AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    AnalysisClauseRepository clauseRepository = mock(AnalysisClauseRepository.class);
    JudgeWriter judgeWriter = mock(JudgeWriter.class);
    FinishStepHandler step = new FinishStepHandler(analysisRepository, clauseRepository, judgeWriter, mapper);

    @Test
    void 진행_단계는_SUMMARIZING이고_1차_날짜로_일정_후보를_저장한다() {
        Analysis analysis = Analysis.create(30L, "EMPLOYMENT", "v", OffsetDateTime.now());
        analysis.recordExtraction("{\"dates\":[{\"label\":\"계약 시작일\",\"date\":\"2026-12-01\",\"clauseKey\":\"c1\"}]}",
                null, null, null, null);
        when(analysisRepository.findById(88L)).thenReturn(Optional.of(analysis));
        when(clauseRepository.findByAnalysisIdOrderByPageNoAscClauseIdAsc(88L)).thenReturn(List.of(
                AnalysisClause.create(88L, "c1", "제1조", null, "원문", "한 줄", 1, null, false, null, null,
                        List.of(), List.of())));

        step.execute(CTX);

        assertThat(step.progressStep()).isEqualTo(AnalysisStep.SUMMARIZING);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScheduleCandidate>> saved = ArgumentCaptor.forClass(List.class);
        verify(judgeWriter).saveScheduleCandidates(eq(88L), saved.capture());
        assertThat(saved.getValue()).extracting(ScheduleCandidate::getDateKind).containsExactly("CONTRACT_START");
        assertThat(saved.getValue().get(0).getLocation()).isEqualTo("1쪽 제1조");
    }
}
