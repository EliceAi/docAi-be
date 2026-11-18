package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AnalysisClauseRepository extends JpaRepository<AnalysisClause, Long> {

    /** 제외되지 않은 조항 중 verdict 가 verdicts 에 드는 수 (확인 필요 수 = NEEDS_CHECK + UNCERTAIN) */
    long countByAnalysisIdAndExcludedFalseAndVerdictIn(Long analysisId, Collection<ClauseVerdict> verdicts);

    /** 조회용 조항 목록: 제외된 조항과 notVerdict(판정 중)를 뺀다. 쪽 순서, 같은 쪽은 저장 순서 */
    List<AnalysisClause> findByAnalysisIdAndExcludedFalseAndVerdictNotOrderByPageNoAscClauseIdAsc(
            Long analysisId, ClauseVerdict notVerdict);

    /** 분석의 모든 조항 (제외·판정 중 포함). 쪽 순서, 같은 쪽은 저장 순서. 2차 판단 단계가 쓴다 */
    List<AnalysisClause> findByAnalysisIdOrderByPageNoAscClauseIdAsc(Long analysisId);
}
