package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.ManualInputCommand;
import com.team.docai.domain.analysis.dto.ManualInputInfo;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.service.query.ContractReadOnlyQuery;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 미인식 항목 입력 (POST /api/analyses/{analysisId}/manual-inputs).
 * 부분 분석에서 읽지 못한 주요 조건 칸에 사용자가 직접 넣은 값을 저장한다.
 * <ul>
 *   <li>입력값은 source = MANUAL 로 표시하고 state(UNREADABLE)는 그대로 둔다. AI 가 읽은 값처럼 다루지 않는다.</li>
 *   <li>조항 판정(확인 필요 여부)·규칙 결과는 다시 계산하지 않는다 (명세 비고).</li>
 *   <li>소유권은 analysis → contract_version → contract.user_id. B 메서드가 없어 지금은 {@link ContractReadOnlyQuery}
 *       (담당_연결지점 "임시 예외")로 확인한다. 임시 예외 사용: B 가 ContractService.getVersionOwner(userId, versionId) 를
 *       만들면 그 메서드로 바꾸고 ContractReadOnlyQuery 의존을 없앤다.</li>
 * </ul>
 * 같은 칸에 동시에 입력하면 나중 것이 남는다. 상태(UNREADABLE)는 바뀌지 않고 COMPLETED 분석은 다시 바뀌지 않으므로 잠그지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManualInputService {

    private final AnalysisRepository analysisRepository;
    private final AnalysisKeyTermRepository analysisKeyTermRepository;
    private final ContractReadOnlyQuery contractQuery;

    /**
     * ① 분석이 없거나 내 것(삭제 안 된 계약서)이 아니면 ANALYSIS_NOT_FOUND ② COMPLETED 가 아니면 ANALYSIS_NOT_COMPLETED
     * ③ 이 분석에 그 이름의 주요 조건이 없으면 TERM_NOT_FOUND ④ 읽지 못한 칸(UNREADABLE)이 아니면 TERM_NOT_EDITABLE
     * ⑤ 값 저장 (source = MANUAL, state 유지).
     */
    @Transactional
    public ManualInputInfo enter(UUID userId, Long analysisId, ManualInputCommand command) {
        Analysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
        contractQuery.findOwnedVersion(userId, analysis.getVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
        if (analysis.getStatus() != AnalysisStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED);
        }
        AnalysisKeyTerm term = analysisKeyTermRepository.findByAnalysisIdAndLabel(analysisId, command.label())
                .orElseThrow(() -> new BusinessException(ErrorCode.TERM_NOT_FOUND));
        if (!term.isManuallyEnterable()) {
            throw new BusinessException(ErrorCode.TERM_NOT_EDITABLE);
        }
        term.enterManually(command.value());
        return ManualInputInfo.from(term);
    }
}
