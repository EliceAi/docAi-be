package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.ManualInputCommand;
import com.team.docai.domain.analysis.dto.ManualInputInfo;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.service.query.ContractReadOnlyQuery;
import com.team.docai.domain.analysis.service.query.ContractReadOnlyQuery.OwnedVersion;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

/**
 * 미인식 항목 입력의 검사 순서(① 분석·소유권 ② 완료 ③ 항목 ④ 입력 가능 칸)와 저장 내용(값, MANUAL, state 유지, value_json 비움).
 * DB 없이 저장소를 흉내 낸다.
 */
class ManualInputServiceTest {

    static final UUID USER = UUID.randomUUID();
    static final long ANALYSIS_ID = 10L;
    static final long VERSION_ID = 20L;
    static final ManualInputCommand COMMAND = new ManualInputCommand("휴일", "매주 일요일");

    AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    AnalysisKeyTermRepository keyTermRepository = mock(AnalysisKeyTermRepository.class);
    ContractReadOnlyQuery contractQuery = mock(ContractReadOnlyQuery.class);
    Analysis analysis = mock(Analysis.class);

    ManualInputService service;

    @BeforeEach
    void setUp() {
        service = new ManualInputService(analysisRepository, keyTermRepository, contractQuery);
        when(analysis.getAnalysisId()).thenReturn(ANALYSIS_ID);
        when(analysis.getVersionId()).thenReturn(VERSION_ID);
        when(analysis.getStatus()).thenReturn(AnalysisStatus.COMPLETED);
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.of(analysis));
        when(contractQuery.findOwnedVersion(USER, VERSION_ID))
                .thenReturn(Optional.of(new OwnedVersion(1L, 1, 2, "근로계약서", 0)));
    }

    @Test
    void 읽지_못한_칸에_값을_넣으면_MANUAL로_표시하고_state는_그대로_둔다() {
        AnalysisKeyTerm term = term(KeyTermState.UNREADABLE, null, Map.of("type", "WEEKLY"));
        when(keyTermRepository.findByAnalysisIdAndLabel(ANALYSIS_ID, "휴일")).thenReturn(Optional.of(term));

        ManualInputInfo info = service.enter(USER, ANALYSIS_ID, COMMAND);

        assertThat(info).isEqualTo(new ManualInputInfo("휴일", "매주 일요일", KeyTermState.UNREADABLE, KeyTermSource.MANUAL));
        assertThat(term.getValue()).isEqualTo("매주 일요일");
        assertThat(term.getSource()).isEqualTo(KeyTermSource.MANUAL);
        assertThat(term.getState()).isEqualTo(KeyTermState.UNREADABLE);
        // AI 가 읽은 계산용 값처럼 쓰이지 않게 비운다
        assertThat(term.getValueJson()).isNull();
        // 조항 ID·근거·순서는 그대로
        assertThat(term.getClauseId()).isEqualTo(5L);
        assertThat(term.getEvidence()).isEqualTo("2쪽 흐림");
        assertThat(term.getSortOrder()).isEqualTo(3);
    }

    @Test
    void 이미_직접_입력한_칸도_다시_입력할_수_있다() {
        AnalysisKeyTerm term = term(KeyTermState.UNREADABLE, null, null);
        term.enterManually("매주 토요일");
        when(keyTermRepository.findByAnalysisIdAndLabel(ANALYSIS_ID, "휴일")).thenReturn(Optional.of(term));

        ManualInputInfo info = service.enter(USER, ANALYSIS_ID, COMMAND);

        assertThat(info.value()).isEqualTo("매주 일요일");
        assertThat(info.state()).isEqualTo(KeyTermState.UNREADABLE);
        assertThat(info.source()).isEqualTo(KeyTermSource.MANUAL);
    }

    @Test
    void 분석이_없으면_ANALYSIS_NOT_FOUND() {
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.empty());

        assertCode(ErrorCode.ANALYSIS_NOT_FOUND);
        verify(contractQuery, never()).findOwnedVersion(any(), anyLong());
    }

    @Test
    void 남의_것이거나_삭제된_계약서의_분석이면_ANALYSIS_NOT_FOUND() {
        when(contractQuery.findOwnedVersion(USER, VERSION_ID)).thenReturn(Optional.empty());
        // 남의 분석이면 상태와 항목을 보지 않는다 (있는지 없는지 알려 주지 않게)
        when(analysis.getStatus()).thenReturn(AnalysisStatus.ANALYZING);

        assertCode(ErrorCode.ANALYSIS_NOT_FOUND);
        verify(keyTermRepository, never()).findByAnalysisIdAndLabel(anyLong(), any());
    }

    @Test
    void 분석_중이거나_실패한_분석이면_ANALYSIS_NOT_COMPLETED() {
        for (AnalysisStatus status : new AnalysisStatus[]{AnalysisStatus.ANALYZING, AnalysisStatus.FAILED}) {
            when(analysis.getStatus()).thenReturn(status);
            assertCode(ErrorCode.ANALYSIS_NOT_COMPLETED);
        }
        verify(keyTermRepository, never()).findByAnalysisIdAndLabel(anyLong(), any());
    }

    @Test
    void 이_분석에_그_이름의_항목이_없으면_TERM_NOT_FOUND() {
        when(keyTermRepository.findByAnalysisIdAndLabel(ANALYSIS_ID, "휴일")).thenReturn(Optional.empty());

        assertCode(ErrorCode.TERM_NOT_FOUND);
    }

    @Test
    void 읽지_못한_칸이_아니면_TERM_NOT_EDITABLE이고_값을_바꾸지_않는다() {
        for (KeyTermState state : new KeyTermState[]{KeyTermState.OK, KeyTermState.NEEDS_CHECK,
                KeyTermState.MISSING, KeyTermState.MASKED}) {
            AnalysisKeyTerm term = term(state, "원래 값", Map.of("amount", 1));
            when(keyTermRepository.findByAnalysisIdAndLabel(ANALYSIS_ID, "휴일")).thenReturn(Optional.of(term));

            assertCode(ErrorCode.TERM_NOT_EDITABLE);
            assertThat(term.getValue()).isEqualTo("원래 값");
            assertThat(term.getSource()).isEqualTo(KeyTermSource.WRITTEN);
            assertThat(term.getValueJson()).isEqualTo(Map.of("amount", 1));
        }
    }

    @Test
    void 엔티티는_읽지_못한_칸이_아니면_직접_입력을_거부한다() {
        AnalysisKeyTerm term = term(KeyTermState.OK, "시급 10,000원", null);

        assertThat(term.isManuallyEnterable()).isFalse();
        assertThatThrownBy(() -> term.enterManually("x")).isInstanceOf(IllegalStateException.class);
        assertThat(term.getValue()).isEqualTo("시급 10,000원");
    }

    private void assertCode(ErrorCode code) {
        assertThatThrownBy(() -> service.enter(USER, ANALYSIS_ID, COMMAND))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    private static AnalysisKeyTerm term(KeyTermState state, String value, Map<String, Object> valueJson) {
        return AnalysisKeyTerm.create(ANALYSIS_ID, 5L, "휴일", value, valueJson, state, KeyTermSource.WRITTEN,
                "2쪽 흐림", 3);
    }
}
