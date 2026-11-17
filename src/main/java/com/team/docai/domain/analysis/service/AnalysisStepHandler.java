package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.AnalysisStep;

/**
 * 분석 파이프라인의 한 단계 (아키텍처 2~9단계를 여기에 꽂는다). 빈으로 등록하고 {@code @Order} 로 순서를 정한다.
 * <ul>
 *   <li>트랜잭션 밖에서 불린다. GPT 는 이 안에서 부르고, 저장은 다른 빈의 짧은 {@code @Transactional} 메서드로 한다.</li>
 *   <li>계약서 행 잠금(lockForUpdate)을 잡지 않는다.</li>
 *   <li>사용자에게 보여 줄 실패(TYPE_MISMATCH, PAGE_UNREADABLE)는 {@link AnalysisFailedException} 을 던진다.
 *       그 밖의 예외(GptCallException 포함)는 FAILED · TEMPORARY_ERROR 가 된다.</li>
 *   <li>분석을 COMPLETED 로 바꾸지 않는다. 모든 단계가 끝나면 파이프라인이 조건부로 바꾼다.</li>
 * </ul>
 */
public interface AnalysisStepHandler {

    /** 이 단계가 도는 동안 앱에 보여 줄 진행 단계 (analysis.progress_step). 단계 시작 때 저장된다. */
    AnalysisStep progressStep();

    void execute(AnalysisRunContext context);
}
