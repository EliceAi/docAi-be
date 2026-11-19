package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisSummaryInfo;
import com.team.docai.domain.analysis.dto.ComparisonSourceInfo;
import com.team.docai.domain.analysis.dto.ScheduleCandidateInfo;
import com.team.docai.domain.analysis.dto.VersionAnalysisStateInfo;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisChunk;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.CandidateSource;
import com.team.docai.domain.analysis.entity.ChunkStatus;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.FindingKind;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.entity.ScheduleCandidate;
import com.team.docai.domain.analysis.repository.AnalysisChunkRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisFindingRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.repository.ScheduleCandidateRepository;
import com.team.docai.domain.analysis.dto.AnalysisRefInfo;
import com.team.docai.domain.contract.dto.ContractVersionInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisServiceTest {

    static final Long ANALYSIS_ID = 88L;
    static final Long VERSION_ID = 7L;
    static final OffsetDateTime STARTED = OffsetDateTime.parse("2026-11-12T14:19:30+09:00");
    static final OffsetDateTime COMPLETED = OffsetDateTime.parse("2026-11-12T14:20:00+09:00");

    AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    AnalysisChunkRepository chunkRepository = mock(AnalysisChunkRepository.class);
    AnalysisKeyTermRepository keyTermRepository = mock(AnalysisKeyTermRepository.class);
    AnalysisClauseRepository clauseRepository = mock(AnalysisClauseRepository.class);
    AnalysisFindingRepository findingRepository = mock(AnalysisFindingRepository.class);
    ScheduleCandidateRepository candidateRepository = mock(ScheduleCandidateRepository.class);
    ContractService contractService = mock(ContractService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<ContractService> contractServiceProvider = mock(ObjectProvider.class);
    AnalysisService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisService(analysisRepository, chunkRepository, keyTermRepository, clauseRepository,
                findingRepository, candidateRepository, contractServiceProvider);
        when(contractServiceProvider.getObject()).thenReturn(contractService);
    }

    static <T> T withId(T entity, String field, Long id) {
        ReflectionTestUtils.setField(entity, field, id);
        return entity;
    }

    static Analysis analysis(AnalysisStatus status) {
        Analysis a = withId(Analysis.create(VERSION_ID, "EMPLOYMENT", "extract-v1/judge-v1", STARTED), "analysisId", ANALYSIS_ID);
        ReflectionTestUtils.setField(a, "status", status);
        if (status != AnalysisStatus.ANALYZING) {
            ReflectionTestUtils.setField(a, "completedAt", COMPLETED);
        }
        return a;
    }

    void givenCompleted() {
        when(analysisRepository.findByAnalysisIdAndStatus(ANALYSIS_ID, AnalysisStatus.COMPLETED))
                .thenReturn(Optional.of(analysis(AnalysisStatus.COMPLETED)));
    }

    void givenNotCompleted() {
        when(analysisRepository.findByAnalysisIdAndStatus(ANALYSIS_ID, AnalysisStatus.COMPLETED))
                .thenReturn(Optional.empty());
    }

    @Test
    void getVersionState_분석이_없으면_분석중_아님과_null() {
        when(analysisRepository.existsByVersionIdAndStatus(VERSION_ID, AnalysisStatus.ANALYZING)).thenReturn(false);
        when(analysisRepository.findFirstByVersionIdOrderByStartedAtDescAnalysisIdDesc(VERSION_ID)).thenReturn(Optional.empty());

        VersionAnalysisStateInfo info = service.getVersionState(VERSION_ID);

        assertThat(info.hasAnalyzing()).isFalse();
        assertThat(info.latestAnalysis()).isNull();
    }

    @Test
    void getVersionState_분석중과_가장_최근_분석을_준다() {
        when(analysisRepository.existsByVersionIdAndStatus(VERSION_ID, AnalysisStatus.ANALYZING)).thenReturn(true);
        when(analysisRepository.findFirstByVersionIdOrderByStartedAtDescAnalysisIdDesc(VERSION_ID))
                .thenReturn(Optional.of(analysis(AnalysisStatus.FAILED)));

        VersionAnalysisStateInfo info = service.getVersionState(VERSION_ID);

        assertThat(info.hasAnalyzing()).isTrue();
        assertThat(info.latestAnalysis()).isEqualTo(
                new VersionAnalysisStateInfo.LatestAnalysis(ANALYSIS_ID, "FAILED", "EMPLOYMENT", STARTED));
    }

    @Test
    void getSummary_완료가_아니면_empty이고_세지_않는다() {
        givenNotCompleted();

        assertThat(service.getSummary(ANALYSIS_ID)).isEmpty();
        verify(clauseRepository, never()).countByAnalysisIdAndExcludedFalseAndVerdictIn(anyLong(), any());
    }

    @Test
    void getSummary_확인필요는_NEEDS_CHECK와_UNCERTAIN_빠진항목은_MISSING_ITEM() {
        givenCompleted();
        when(clauseRepository.countByAnalysisIdAndExcludedFalseAndVerdictIn(ANALYSIS_ID,
                List.of(ClauseVerdict.NEEDS_CHECK, ClauseVerdict.UNCERTAIN))).thenReturn(2L);
        when(findingRepository.countByAnalysisIdAndKind(ANALYSIS_ID, FindingKind.MISSING_ITEM)).thenReturn(1L);
        when(candidateRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(4L);

        assertThat(service.getSummary(ANALYSIS_ID))
                .contains(new AnalysisSummaryInfo(ANALYSIS_ID, null, COMPLETED, 2, 1, 4));
    }

    @Test
    void getScheduleCandidates_완료가_아니면_빈_목록() {
        givenNotCompleted();

        assertThat(service.getScheduleCandidates(ANALYSIS_ID)).isEmpty();
        verify(candidateRepository, never()).findByAnalysisIdOrderByCandidateIdAsc(anyLong());
    }

    @Test
    void getScheduleCandidates_완료면_후보를_준다() {
        givenCompleted();
        ScheduleCandidate written = withId(ScheduleCandidate.create(ANALYSIS_ID, "CONTRACT_END", "계약 종료",
                LocalDate.of(2027, 2, 28), CandidateSource.WRITTEN, "1쪽 제1조", 1, "CONTRACT_END", false), "candidateId", 304L);
        ScheduleCandidate missing = withId(ScheduleCandidate.create(ANALYSIS_ID, "RENEWAL_NOTICE", "갱신 통보 기한",
                null, CandidateSource.REVIEW, null, null, null, false), "candidateId", 306L);
        when(candidateRepository.findByAnalysisIdOrderByCandidateIdAsc(ANALYSIS_ID)).thenReturn(List.of(written, missing));

        List<ScheduleCandidateInfo> infos = service.getScheduleCandidates(ANALYSIS_ID);

        assertThat(infos).containsExactly(
                new ScheduleCandidateInfo(304L, "CONTRACT_END", "계약 종료", LocalDate.of(2027, 2, 28), "WRITTEN",
                        "1쪽 제1조", 1, "CONTRACT_END", false),
                new ScheduleCandidateInfo(306L, "RENEWAL_NOTICE", "갱신 통보 기한", null, "REVIEW",
                        null, null, null, false));
    }

    @Test
    void getComparisonSource_완료가_아니면_empty() {
        givenNotCompleted();

        assertThat(service.getComparisonSource(ANALYSIS_ID)).isEmpty();
        verify(clauseRepository, never()).findByAnalysisIdAndExcludedFalseAndVerdictNotOrderByPageNoAscClauseIdAsc(anyLong(), any());
    }

    @Test
    void getComparisonSource_판정중을_빼고_읽은_쪽은_DONE_묶음으로_센다() {
        givenCompleted();
        AnalysisChunk first = AnalysisChunk.create(ANALYSIS_ID, 1, 1, 5);
        ReflectionTestUtils.setField(first, "status", ChunkStatus.DONE);
        AnalysisChunk second = AnalysisChunk.create(ANALYSIS_ID, 2, 5, 7); // 경계 쪽 5 겹침, 아직 PENDING
        when(chunkRepository.findByAnalysisIdOrderByChunkNoAsc(ANALYSIS_ID)).thenReturn(List.of(first, second));
        Map<String, Object> wageJson = new HashMap<>();
        wageJson.put("type", "HOURLY");
        wageJson.put("amount", 10000);
        wageJson.put("unit", null); // 값에 null 이 있어도 복사된다
        AnalysisKeyTerm wage = AnalysisKeyTerm.create(ANALYSIS_ID, null, "임금", "시급 10,000원",
                wageJson, KeyTermState.NEEDS_CHECK, KeyTermSource.WRITTEN, "1쪽 제4조", 1);
        when(keyTermRepository.findByAnalysisIdOrderBySortOrderAsc(ANALYSIS_ID)).thenReturn(List.of(wage));
        AnalysisClause unreadable = withId(AnalysisClause.create(ANALYSIS_ID, "c2", "제2조", null, null, null, 2,
                null, false, null, null, null, null), "clauseId", 702L);
        // 원문이 남아 있어도 UNREADABLE 이면 originalText 는 null 로 내보낸다
        AnalysisClause unreadableWithText = withId(AnalysisClause.create(ANALYSIS_ID, "c3", "제3조", null, "흐린 글자",
                null, 2, null, false, null, null, null, null), "clauseId", 703L);
        ReflectionTestUtils.setField(unreadableWithText, "verdict", ClauseVerdict.UNREADABLE);
        when(clauseRepository.findByAnalysisIdAndExcludedFalseAndVerdictNotOrderByPageNoAscClauseIdAsc(
                ANALYSIS_ID, ClauseVerdict.JUDGING)).thenReturn(List.of(unreadable, unreadableWithText));

        ComparisonSourceInfo info = service.getComparisonSource(ANALYSIS_ID).orElseThrow();

        assertThat(info.analysisId()).isEqualTo(ANALYSIS_ID);
        assertThat(info.documentTypeCode()).isEqualTo("EMPLOYMENT");
        assertThat(info.pageCount()).isEqualTo(7);
        assertThat(info.readPages()).containsExactly(1, 2, 3, 4, 5);
        assertThat(info.partialPages()).isEmpty();
        assertThat(info.keyTerms()).hasSize(1);
        ComparisonSourceInfo.KeyTerm term = info.keyTerms().get(0);
        assertThat(term.label()).isEqualTo("임금");
        assertThat(term.state()).isEqualTo("NEEDS_CHECK");
        assertThat(term.valueJson()).isEqualTo(wageJson).isNotSameAs(wageJson).containsEntry("unit", null);
        assertThatThrownBy(() -> term.valueJson().put("amount", 1)).isInstanceOf(UnsupportedOperationException.class);
        wageJson.put("amount", 99999); // 엔티티 쪽 값이 바뀌어도 Info 는 그대로
        assertThat(term.valueJson()).containsEntry("amount", 10000);
        assertThat(info.clauses()).containsExactly(
                new ComparisonSourceInfo.Clause(702L, "제2조", null, null, 2, "UNREADABLE"),
                new ComparisonSourceInfo.Clause(703L, "제3조", null, null, 2, "UNREADABLE"));
    }

    @Test
    void clearSimilarContract_버전의_분석을_비운다() {
        service.clearSimilarContract(VERSION_ID);

        verify(analysisRepository).clearSimilarContract(VERSION_ID);
    }

    @Test
    void getRef_내_분석이면_분석_버전_계약서_상태_완료시각을_돌려준다() {
        UUID user = UUID.randomUUID();
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.of(analysis(AnalysisStatus.COMPLETED)));
        when(contractService.getVersionOwner(user, VERSION_ID)).thenReturn(new ContractVersionInfo(12L, 1, 2, "근로계약서", 0));

        AnalysisRefInfo ref = service.getRef(user, ANALYSIS_ID);

        assertThat(ref).isEqualTo(new AnalysisRefInfo(ANALYSIS_ID, VERSION_ID, 12L, "COMPLETED", COMPLETED));
        verify(contractService).getVersionOwner(user, VERSION_ID);
    }

    @Test
    void getRef_완료되지_않은_분석도_상태만_담아_돌려준다() {
        UUID user = UUID.randomUUID();
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.of(analysis(AnalysisStatus.ANALYZING)));
        when(contractService.getVersionOwner(user, VERSION_ID)).thenReturn(new ContractVersionInfo(12L, 1, 2, null, 0));

        AnalysisRefInfo ref = service.getRef(user, ANALYSIS_ID);

        assertThat(ref.status()).isEqualTo("ANALYZING");
        assertThat(ref.completedAt()).isNull();
    }

    @Test
    void getRef_분석이_없으면_B를_부르지_않고_ANALYSIS_NOT_FOUND() {
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.empty());

        assertRefCode(UUID.randomUUID(), ErrorCode.ANALYSIS_NOT_FOUND);
        verify(contractService, never()).getVersionOwner(any(), anyLong());
    }

    @Test
    void getRef_남의_분석이면_B의_CONTRACT_NOT_FOUND를_ANALYSIS_NOT_FOUND로_바꾼다() {
        UUID other = UUID.randomUUID();
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.of(analysis(AnalysisStatus.COMPLETED)));
        when(contractService.getVersionOwner(other, VERSION_ID))
                .thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        assertRefCode(other, ErrorCode.ANALYSIS_NOT_FOUND);
    }

    @Test
    void getRef_계약서가_삭제됐으면_ANALYSIS_NOT_FOUND() {
        // B getVersionOwner 는 삭제된 계약서(deleted_at 있음)도 CONTRACT_NOT_FOUND 로 던진다 (AnalysisContractMethodsTest)
        UUID user = UUID.randomUUID();
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.of(analysis(AnalysisStatus.FAILED)));
        when(contractService.getVersionOwner(user, VERSION_ID))
                .thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        assertRefCode(user, ErrorCode.ANALYSIS_NOT_FOUND);
    }

    @Test
    void getRef_B의_다른_예외는_그대로_올린다() {
        UUID user = UUID.randomUUID();
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.of(analysis(AnalysisStatus.COMPLETED)));
        when(contractService.getVersionOwner(user, VERSION_ID))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));

        assertRefCode(user, ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    void getRef_userId나_analysisId가_없으면_ANALYSIS_NOT_FOUND() {
        assertRefCode(null, ErrorCode.ANALYSIS_NOT_FOUND);
        assertThatThrownBy(() -> service.getRef(UUID.randomUUID(), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_FOUND));
        verify(analysisRepository, never()).findById(any());
    }

    private void assertRefCode(UUID userId, ErrorCode code) {
        assertThatThrownBy(() -> service.getRef(userId, ANALYSIS_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
