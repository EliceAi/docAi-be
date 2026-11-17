package com.team.docai.domain.analysis.dto;

/**
 * 분석을 새로 만들었다는 이벤트. 요청 트랜잭션이 커밋된 뒤에만 파이프라인을 시작한다
 * (AnalysisPipelineLauncher, AFTER_COMMIT 에서 실행 목록 등록 후 전용 스레드 풀에 제출). 롤백되면 전달되지 않는다.
 */
public record AnalysisRequestedEvent(AnalysisRunContext context) {
}
