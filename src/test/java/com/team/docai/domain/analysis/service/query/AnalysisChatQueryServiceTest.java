package com.team.docai.domain.analysis.service.query;

import com.team.docai.domain.analysis.dto.AnalysisChatInfo;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
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

/** 챗봇용 분석 읽기의 주인 확인 (B getVersionOwner). B 의 예외는 empty 로 삼키지 않는다. */
class AnalysisChatQueryServiceTest {

    static final UUID USER = UUID.randomUUID();
    static final long ANALYSIS_ID = 10L;
    static final long VERSION_ID = 20L;

    AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    AnalysisKeyTermRepository keyTermRepository = mock(AnalysisKeyTermRepository.class);
    AnalysisClauseRepository clauseRepository = mock(AnalysisClauseRepository.class);
    AnalysisResultReader reader = mock(AnalysisResultReader.class);
    ContractService contractService = mock(ContractService.class);
    LawSearchService lawSearchService = mock(LawSearchService.class);
    AnalysisChatQueryService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisChatQueryService(analysisRepository, keyTermRepository, clauseRepository, reader,
                contractService, lawSearchService);
        Analysis analysis = mock(Analysis.class);
        when(analysis.getAnalysisId()).thenReturn(ANALYSIS_ID);
        when(analysis.getVersionId()).thenReturn(VERSION_ID);
        when(analysis.getStatus()).thenReturn(AnalysisStatus.ANALYZING);
        when(analysis.getDocumentTypeCode()).thenReturn("EMPLOYMENT");
        when(analysisRepository.findById(ANALYSIS_ID)).thenReturn(Optional.of(analysis));
    }

    @Test
    void 내_분석이면_B가_준_계약서_ID로_돌려준다() {
        when(contractService.getVersionOwner(USER, VERSION_ID)).thenReturn(new ContractVersionInfo(31L, 1, 2, "계약서", 0));

        Optional<AnalysisChatInfo> info = service.findForChat(USER, ANALYSIS_ID);

        assertThat(info).isPresent();
        assertThat(info.get().analysisId()).isEqualTo(ANALYSIS_ID);
        assertThat(info.get().contractId()).isEqualTo(31L);
        assertThat(info.get().status()).isEqualTo(AnalysisStatus.ANALYZING);
    }

    @Test
    void 분석이_없으면_B를_부르지_않고_empty() {
        assertThat(service.findForChat(USER, 999L)).isEmpty();
        assertThat(service.findForChat(null, ANALYSIS_ID)).isEmpty();
        assertThat(service.findForChat(USER, null)).isEmpty();
        verify(contractService, never()).getVersionOwner(any(), anyLong());
    }

    @Test
    void 남의_것이거나_삭제된_계약서면_CONTRACT_NOT_FOUND를_삼키지_않고_올린다() {
        when(contractService.getVersionOwner(USER, VERSION_ID))
                .thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        assertThatThrownBy(() -> service.findForChat(USER, ANALYSIS_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONTRACT_NOT_FOUND));
    }

    @Test
    void B의_다른_예외도_그대로_올린다() {
        IllegalStateException boom = new IllegalStateException("db");
        when(contractService.getVersionOwner(USER, VERSION_ID)).thenThrow(boom);

        assertThatThrownBy(() -> service.findForChat(USER, ANALYSIS_ID)).isSameAs(boom);
    }
}
