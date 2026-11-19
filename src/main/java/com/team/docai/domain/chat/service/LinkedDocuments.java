package com.team.docai.domain.chat.service;

import com.team.docai.domain.analysis.dto.AnalysisSummaryInfo;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.chat.dto.LinkedDocumentInfo;
import com.team.docai.domain.contract.dto.ContractSummaryInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * linkedDocument {contractId, title, analyzedAt} 만들기 (AI 질문 응답과 대화 기록 조회가 같은 모양).
 * analyzedAt 은 계약서 최신 버전의 최신 완료 분석(B7 getSummary 의 latestAnalysisId) 완료 시각, 앱 표시 시간대(Asia/Seoul)로.
 * 부르는 쪽 트랜잭션 안에서 부른다.
 */
@Component
@RequiredArgsConstructor
class LinkedDocuments {

    private final AnalysisService analysisService;
    private final Clock clock;

    LinkedDocumentInfo from(ContractSummaryInfo contract) {
        OffsetDateTime analyzedAt = contract.latestAnalysisId() == null ? null
                : analysisService.getSummary(contract.latestAnalysisId())
                .map(AnalysisSummaryInfo::completedAt)
                .map(t -> t.atZoneSameInstant(clock.getZone()).toOffsetDateTime())
                .orElse(null);
        return new LinkedDocumentInfo(contract.contractId(), contract.title(), analyzedAt);
    }
}
