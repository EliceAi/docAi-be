package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRequestInfo;
import com.team.docai.domain.analysis.dto.AnalysisRequestedEvent;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisChunk;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.ChunkStatus;
import com.team.docai.domain.analysis.repository.AnalysisChunkRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.contract.dto.ContractForAnalysisInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.config.AnalysisProperties;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisRequestWriterTest {

    static final UUID USER = UUID.randomUUID();
    static final Long CONTRACT_ID = 12L;
    static final Long VERSION_ID = 30L;

    ContractService contractService = mock(ContractService.class);
    AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    AnalysisChunkRepository chunkRepository = mock(AnalysisChunkRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    AnalysisProperties properties = new AnalysisProperties("extract-v1/judge-v1", 5, 5, 4, 100, Duration.ofMinutes(2),
            Duration.ofSeconds(15), Duration.ofSeconds(30), 2);
    AnalysisRequestWriter writer = new AnalysisRequestWriter(contractService, analysisRepository, chunkRepository, publisher, properties);

    static ContractForAnalysisInfo contract(boolean archived, int pageCount) {
        List<ContractForAnalysisInfo.PageInfo> pages = IntStream.rangeClosed(1, pageCount)
                .mapToObj(i -> new ContractForAnalysisInfo.PageInfo(i, "contracts/u/" + i + ".jpg", null)).toList();
        return new ContractForAnalysisInfo(CONTRACT_ID, VERSION_ID, "EMPLOYMENT", archived, pages);
    }

    void givenSavedAnalysisGetsId(long id) {
        when(analysisRepository.saveAndFlush(any(Analysis.class))).thenAnswer(inv -> {
            Analysis a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "analysisId", id);
            return a;
        });
    }

    @Test
    void 쪽_묶음은_5쪽씩_겹치지_않게() {
        assertThat(AnalysisRequestWriter.chunkRanges(List.of(1, 2), 5)).containsExactly(new int[]{1, 2});
        assertThat(AnalysisRequestWriter.chunkRanges(IntStream.rangeClosed(1, 12).boxed().toList(), 5))
                .containsExactly(new int[]{1, 5}, new int[]{6, 10}, new int[]{11, 12});
        assertThat(AnalysisRequestWriter.chunkRanges(IntStream.rangeClosed(1, 5).boxed().toList(), 5))
                .containsExactly(new int[]{1, 5});
        assertThat(AnalysisRequestWriter.chunkRanges(List.of(), 5)).isEmpty();
    }

    @Test
    void 잠금_다음_조회_다음_생성_그리고_이벤트() {
        when(contractService.getForAnalysis(USER, CONTRACT_ID)).thenReturn(contract(false, 12));
        when(analysisRepository.findByVersionIdAndStatus(VERSION_ID, AnalysisStatus.ANALYZING)).thenReturn(Optional.empty());
        givenSavedAnalysisGetsId(88L);

        AnalysisRequestInfo info = writer.createOrGetRunning(USER, CONTRACT_ID);

        assertThat(info).isEqualTo(new AnalysisRequestInfo(88L, CONTRACT_ID, AnalysisStatus.ANALYZING,
                AnalysisStep.READING_TEXT, 0, 3));
        InOrder order = inOrder(contractService, analysisRepository, chunkRepository, publisher);
        order.verify(contractService).lockForUpdate(CONTRACT_ID);
        order.verify(contractService).getForAnalysis(USER, CONTRACT_ID);
        order.verify(analysisRepository).findByVersionIdAndStatus(VERSION_ID, AnalysisStatus.ANALYZING);
        ArgumentCaptor<Analysis> saved = ArgumentCaptor.forClass(Analysis.class);
        order.verify(analysisRepository).saveAndFlush(saved.capture());
        order.verify(chunkRepository).saveAll(anyList());
        order.verify(publisher).publishEvent(new AnalysisRequestedEvent(
                new AnalysisRunContext(88L, USER, CONTRACT_ID, VERSION_ID, "EMPLOYMENT")));

        Analysis a = saved.getValue();
        assertThat(a.getVersionId()).isEqualTo(VERSION_ID);
        assertThat(a.getDocumentTypeCode()).isEqualTo("EMPLOYMENT");
        assertThat(a.getStatus()).isEqualTo(AnalysisStatus.ANALYZING);
        assertThat(a.getProgressStep()).isEqualTo(AnalysisStep.READING_TEXT);
        assertThat(a.getPromptVersion()).isEqualTo("extract-v1/judge-v1");
        assertThat(a.getStartedAt()).isNotNull();
        assertThat(a.getHeartbeatAt()).isEqualTo(a.getStartedAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 쪽_묶음을_PENDING으로_저장한다() {
        when(contractService.getForAnalysis(USER, CONTRACT_ID)).thenReturn(contract(false, 7));
        when(analysisRepository.findByVersionIdAndStatus(VERSION_ID, AnalysisStatus.ANALYZING)).thenReturn(Optional.empty());
        givenSavedAnalysisGetsId(88L);

        writer.createOrGetRunning(USER, CONTRACT_ID);

        ArgumentCaptor<List<AnalysisChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveAll(chunks.capture());
        assertThat(chunks.getValue()).extracting(AnalysisChunk::getAnalysisId, AnalysisChunk::getChunkNo,
                        AnalysisChunk::getPageFrom, AnalysisChunk::getPageTo, AnalysisChunk::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(88L, 1, 1, 5, ChunkStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple(88L, 2, 6, 7, ChunkStatus.PENDING));
    }

    @Test
    void 보관된_계약서는_CONTRACT_ARCHIVED이고_만들지_않는다() {
        when(contractService.getForAnalysis(USER, CONTRACT_ID)).thenReturn(contract(true, 2));

        assertThatThrownBy(() -> writer.createOrGetRunning(USER, CONTRACT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CONTRACT_ARCHIVED));
        verify(contractService).lockForUpdate(CONTRACT_ID);
        verify(analysisRepository, never()).saveAndFlush(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void 계약서가_없으면_B의_CONTRACT_NOT_FOUND를_그대로_올린다() {
        when(contractService.getForAnalysis(USER, CONTRACT_ID)).thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        assertThatThrownBy(() -> writer.createOrGetRunning(USER, CONTRACT_ID))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CONTRACT_NOT_FOUND));
        verify(analysisRepository, never()).saveAndFlush(any());
    }

    @Test
    void 같은_버전에_분석_중이면_새로_만들지_않고_그것을_돌려준다() {
        when(contractService.getForAnalysis(USER, CONTRACT_ID)).thenReturn(contract(false, 12));
        Analysis running = Analysis.create(VERSION_ID, "EMPLOYMENT", "extract-v1/judge-v1", OffsetDateTime.now());
        ReflectionTestUtils.setField(running, "analysisId", 77L);
        ReflectionTestUtils.setField(running, "progressStep", AnalysisStep.FINDING_TERMS);
        when(analysisRepository.findByVersionIdAndStatus(VERSION_ID, AnalysisStatus.ANALYZING)).thenReturn(Optional.of(running));
        when(chunkRepository.countByAnalysisIdAndStatus(77L, ChunkStatus.DONE)).thenReturn(3L);
        when(chunkRepository.countByAnalysisId(77L)).thenReturn(3L);

        AnalysisRequestInfo info = writer.createOrGetRunning(USER, CONTRACT_ID);

        assertThat(info).isEqualTo(new AnalysisRequestInfo(77L, CONTRACT_ID, AnalysisStatus.ANALYZING,
                AnalysisStep.FINDING_TERMS, 3, 3));
        verify(analysisRepository, never()).saveAndFlush(any());
        verify(publisher, never()).publishEvent(any());
    }
}
