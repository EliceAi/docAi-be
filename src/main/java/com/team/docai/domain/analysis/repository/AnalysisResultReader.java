package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.AnalysisClauseRelation;
import com.team.docai.domain.analysis.entity.AnalysisFinding;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 분석 결과·조항 상세 조회용 읽기 쿼리 (A 테이블만). 기존 Repository 에 없는 여러 조항 묶음 조회를 JPQL 로 한다.
 * "보이는 조항" = 제외되지 않고(excluded = false) 판정 중(JUDGING)이 아닌 조항.
 */
@Component
@RequiredArgsConstructor
public class AnalysisResultReader {

    private final EntityManager em;

    /** 이 분석의 보이는 조항 하나. 다른 분석의 조항·제외·판정 중이면 empty */
    public Optional<AnalysisClause> findVisibleClause(Long analysisId, Long clauseId) {
        return em.createQuery("select c from AnalysisClause c where c.clauseId = :clauseId"
                        + " and c.analysisId = :analysisId and c.excluded = false and c.verdict <> :judging",
                        AnalysisClause.class)
                .setParameter("clauseId", clauseId)
                .setParameter("analysisId", analysisId)
                .setParameter("judging", ClauseVerdict.JUDGING)
                .getResultStream()
                .findFirst();
    }

    /** 이 분석의 보이는 조항 중 clauseIds 에 드는 것 (쪽, 조항 ID 순) */
    public List<AnalysisClause> findVisibleClauses(Long analysisId, Collection<Long> clauseIds) {
        if (clauseIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select c from AnalysisClause c where c.analysisId = :analysisId"
                        + " and c.clauseId in :ids and c.excluded = false and c.verdict <> :judging"
                        + " order by c.pageNo, c.clauseId", AnalysisClause.class)
                .setParameter("analysisId", analysisId)
                .setParameter("ids", clauseIds)
                .setParameter("judging", ClauseVerdict.JUDGING)
                .getResultList();
    }

    /** 인용되고 검증을 통과한(cited) 조문 근거. 해석 메모(guidance)는 빼고 조항·ref ID 순 */
    public List<AnalysisClauseRef> findCitedLawRefs(Collection<Long> clauseIds) {
        if (clauseIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select r from AnalysisClauseRef r where r.clauseId in :ids"
                        + " and r.cited = true and r.lawArticleId is not null order by r.clauseId, r.refId",
                        AnalysisClauseRef.class)
                .setParameter("ids", clauseIds)
                .getResultList();
    }

    public List<AnalysisClauseRelation> findRelations(Collection<Long> clauseIds) {
        if (clauseIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select r from AnalysisClauseRelation r where r.clauseId in :ids"
                        + " order by r.clauseId, r.relatedClauseId", AnalysisClauseRelation.class)
                .setParameter("ids", clauseIds)
                .getResultList();
    }

    /** 빠진 항목·기한 (표시 순서) */
    public List<AnalysisFinding> findFindings(Long analysisId) {
        return em.createQuery("select f from AnalysisFinding f where f.analysisId = :analysisId"
                        + " order by f.sortOrder, f.findingId", AnalysisFinding.class)
                .setParameter("analysisId", analysisId)
                .getResultList();
    }

    /** 찾은 날짜 수: 일정 후보 중 날짜가 있는 것 (날짜 없는 누락 후보는 세지 않음) */
    public long countFoundDates(Long analysisId) {
        return em.createQuery("select count(s) from ScheduleCandidate s where s.analysisId = :analysisId"
                        + " and s.date is not null", Long.class)
                .setParameter("analysisId", analysisId)
                .getSingleResult();
    }
}
