package com.team.docai.domain.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRelation;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.entity.LawHint;
import com.team.docai.domain.analysis.repository.AnalysisChunkRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRelationRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 병합 결과 → Entity 매핑 (조항 JUDGING, 키 → clause_id, 분석 칸) */
class ExtractWriterTest {

    AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    AnalysisClauseRepository clauseRepository = mock(AnalysisClauseRepository.class);
    AnalysisClauseRelationRepository relationRepository = mock(AnalysisClauseRelationRepository.class);
    AnalysisKeyTermRepository keyTermRepository = mock(AnalysisKeyTermRepository.class);
    ExtractWriter writer = new ExtractWriter(analysisRepository, mock(AnalysisChunkRepository.class), clauseRepository,
            relationRepository, keyTermRepository, new ObjectMapper());

    static ExtractMerger.Result result() {
        List<ExtractMerger.MergedClause> clauses = List.of(
                new ExtractMerger.MergedClause("c1", "제1조", "계약기간", "원문1", "요약1", 1, "1쪽 위", false, null, null,
                        List.of(), List.of()),
                new ExtractMerger.MergedClause("c2", "제7조", null, "원문2", "요약2", 2, "2쪽", true, "의심",
                        "PENALTY_ON_RESIGNATION", List.of(new LawHint("근로기준법", "제20조")), List.of("위약금")),
                new ExtractMerger.MergedClause("c3", null, null, null, "못 읽음", 2, null, false, null, null,
                        List.of(), List.of()));
        List<ExtractMerger.MergedKeyTerm> terms = List.of(
                new ExtractMerger.MergedKeyTerm("임금", "시급 10,000원", Map.of("type", "HOURLY", "amount", 10000L),
                        KeyTermState.OK, KeyTermSource.WRITTEN, "c2", "근거", 0),
                new ExtractMerger.MergedKeyTerm("휴일", null, null, KeyTermState.MISSING, null, null, null, 1));
        return new ExtractMerger.Result(null, clauses, List.of(new ExtractMerger.Relation("c2", "c1")), terms,
                List.of(2), LocalDate.of(2026, 11, 20), Map.of("promptVersion", "extract-v1"),
                List.of(Map.of("clauseKey", "c1")), List.of(Map.of("stage", "EXTRACT", "type", "TOO_LONG")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 조항은_판정_중으로_저장하고_관련_조항과_주요_조건의_키를_조항_ID로_바꾼다() {
        when(analysisRepository.advance(eq(88L), eq(AnalysisStep.FINDING_TERMS), any(OffsetDateTime.class))).thenReturn(1);
        AtomicLong ids = new AtomicLong(700);
        when(clauseRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            List<AnalysisClause> list = new java.util.ArrayList<>();
            ((Iterable<AnalysisClause>) inv.getArgument(0)).forEach(c -> {
                ReflectionTestUtils.setField(c, "clauseId", ids.incrementAndGet());
                list.add(c);
            });
            return list;
        });
        Analysis analysis = Analysis.create(30L, "EMPLOYMENT", "extract-v1", OffsetDateTime.now());
        when(analysisRepository.findById(88L)).thenReturn(Optional.of(analysis));

        assertThat(writer.saveMerged(88L, result())).isTrue();

        ArgumentCaptor<Iterable<AnalysisClause>> clauses = ArgumentCaptor.forClass(Iterable.class);
        verify(clauseRepository).saveAll(clauses.capture());
        List<AnalysisClause> saved = new java.util.ArrayList<>();
        clauses.getValue().forEach(saved::add);
        assertThat(saved).extracting(AnalysisClause::getClauseKey, AnalysisClause::getVerdict, AnalysisClause::isExcluded)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("c1", ClauseVerdict.JUDGING, false),
                        org.assertj.core.groups.Tuple.tuple("c2", ClauseVerdict.JUDGING, false),
                        org.assertj.core.groups.Tuple.tuple("c3", ClauseVerdict.UNREADABLE, false));
        assertThat(saved.get(1).getIssueTagCode()).isEqualTo("PENALTY_ON_RESIGNATION");
        assertThat(saved.get(1).getLawHints()).containsExactly(new LawHint("근로기준법", "제20조"));

        ArgumentCaptor<Iterable<AnalysisClauseRelation>> relations = ArgumentCaptor.forClass(Iterable.class);
        verify(relationRepository).saveAll(relations.capture());
        assertThat(relations.getValue()).extracting(AnalysisClauseRelation::getAnalysisId,
                        AnalysisClauseRelation::getClauseId, AnalysisClauseRelation::getRelatedClauseId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(88L, 702L, 701L));

        ArgumentCaptor<Iterable<AnalysisKeyTerm>> terms = ArgumentCaptor.forClass(Iterable.class);
        verify(keyTermRepository).saveAll(terms.capture());
        assertThat(terms.getValue()).extracting(AnalysisKeyTerm::getLabel, AnalysisKeyTerm::getClauseId,
                        AnalysisKeyTerm::getState, AnalysisKeyTerm::getSortOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("임금", 702L, KeyTermState.OK, 0),
                        org.assertj.core.groups.Tuple.tuple("휴일", null, KeyTermState.MISSING, 1));

        assertThat(analysis.getExtractionJson()).isEqualTo("{\"promptVersion\":\"extract-v1\"}");
        assertThat(analysis.getClauseIndexJson()).isEqualTo("[{\"clauseKey\":\"c1\"}]");
        assertThat(analysis.getPartialPages()).containsExactly(2);
        assertThat(analysis.getLawBaseDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(analysis.getValidationLogJson()).startsWith("{\"extract\":[").contains("TOO_LONG");
    }

    @Test
    void 이미_끝난_분석이면_아무것도_저장하지_않는다() {
        when(analysisRepository.advance(eq(88L), eq(AnalysisStep.FINDING_TERMS), any(OffsetDateTime.class))).thenReturn(0);

        assertThat(writer.saveMerged(88L, result())).isFalse();

        verify(clauseRepository, never()).saveAll(anyIterable());
        verify(keyTermRepository, never()).saveAll(anyIterable());
        verify(analysisRepository, never()).findById(any());
    }
}
