package com.team.docai.domain.analysis.service;

import com.team.docai.domain.contract.dto.ContractVersionInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;

import java.util.UUID;

/**
 * 분석 주인 확인 (analysis.version_id → B {@code ContractService.getVersionOwner}).
 * B 는 남의 것·삭제된 계약서면 CONTRACT_NOT_FOUND 를 던지고, 분석 API 는 그것을 ANALYSIS_NOT_FOUND 로 바꿔 내보낸다
 * (명세: "분석이 없거나 내 분석이 아님, 삭제된 계약서의 분석 포함"). 호출한 쪽 트랜잭션 안에서 부른다.
 */
public final class AnalysisOwnership {

    private AnalysisOwnership() {
    }

    /** 내 분석이 아니면 ANALYSIS_NOT_FOUND. CONTRACT_NOT_FOUND 밖의 B 예외는 그대로 올린다 */
    /**
     * 주의: B 메서드는 트랜잭션에 참여하므로 CONTRACT_NOT_FOUND 를 던지면 바깥 트랜잭션이 rollback-only 가 된다.
     * 그래서 여기서는 받은 예외를 삼키지 않고 반드시 ANALYSIS_NOT_FOUND 로 바꿔 던진다 (예외 없이 이어 가면 커밋 때
     * UnexpectedRollbackException 이 난다).
     */
    public static ContractVersionInfo requireOwnedVersion(ContractService contractService, UUID userId, Long versionId) {
        if (userId == null || versionId == null) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND);
        }
        try {
            return contractService.getVersionOwner(userId, versionId);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.CONTRACT_NOT_FOUND) {
                throw new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND);
            }
            throw e;
        }
    }
}
