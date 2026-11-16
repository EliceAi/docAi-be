package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.entity.AiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    /**
     * 분석 하나에서 그 목적으로 GPT 를 부른 횟수 (ERD: 추가 조회는 분석당 JUDGE_MORE 1건까지, 이 테이블로 센다).
     * 기록은 시도마다 남으므로 RETRY 행은 빼고, 호출마다 하나씩 남는 마지막 행(OK 또는 FAILED)만 센다.
     */
    @Query("select count(l) from AiCallLog l where l.analysisId = :analysisId and l.purpose = :purpose"
            + " and l.status <> com.team.docai.domain.analysis.entity.AiCallStatus.RETRY")
    long countCalls(@Param("analysisId") Long analysisId, @Param("purpose") GptPurpose purpose);
}
