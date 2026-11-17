package com.team.docai.domain.analysis.repository;

import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

    boolean existsByVersionIdAndStatus(Long versionId, AnalysisStatus status);

    /** 이 버전의 가장 최근 분석 (시작 시각, 같으면 ID 가 큰 것) */
    Optional<Analysis> findFirstByVersionIdOrderByStartedAtDescAnalysisIdDesc(Long versionId);

    Optional<Analysis> findByAnalysisIdAndStatus(Long analysisId, AnalysisStatus status);

    /** 이 버전의 그 상태 분석. ANALYZING 은 uq_analysis_running 때문에 버전마다 하나뿐이다 */
    Optional<Analysis> findByVersionIdAndStatus(Long versionId, AnalysisStatus status);

    /**
     * 단계 시작: 분석 중일 때만 진행 단계를 바꾸고 heartbeat 를 갱신한다.
     * 반환값이 1이 아니면 이미 다른 곳(멈춘 분석 정리 등)이 끝낸 분석이므로 파이프라인을 멈춘다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Analysis a set a.progressStep = :step, a.heartbeatAt = :now"
            + " where a.analysisId = :analysisId"
            + " and a.status = com.team.docai.domain.analysis.entity.AnalysisStatus.ANALYZING")
    int advance(@Param("analysisId") Long analysisId,
                @Param("step") AnalysisStep step,
                @Param("now") OffsetDateTime now);

    /** heartbeat 갱신: 이 서버가 돌리는 분석 중 아직 ANALYZING 인 것만. 바뀐 행 수를 돌려준다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Analysis a set a.heartbeatAt = :now where a.analysisId in :analysisIds"
            + " and a.status = com.team.docai.domain.analysis.entity.AnalysisStatus.ANALYZING")
    int touchHeartbeat(@Param("analysisIds") Collection<Long> analysisIds, @Param("now") OffsetDateTime now);

    /** heartbeat 가 cutoff 전에 멈춘 ANALYZING 분석 ID (heartbeat 가 없으면 시작 시각으로 본다) */
    @Query("select a.analysisId from Analysis a"
            + " where a.status = com.team.docai.domain.analysis.entity.AnalysisStatus.ANALYZING"
            + " and (a.heartbeatAt < :cutoff or (a.heartbeatAt is null and a.startedAt < :cutoff))"
            + " order by a.analysisId")
    List<Long> findStaleAnalyzingIds(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * 멈춘 분석 정리: 아직 ANALYZING 이고 heartbeat 가 cutoff 전에 멈춰 있을 때만 FAILED(reason)로 바꾼다.
     * 조회와 이 UPDATE 사이에 heartbeat 가 갱신됐으면 0행 (살아 있는 작업을 실패로 바꾸지 않게).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Analysis a set a.status = com.team.docai.domain.analysis.entity.AnalysisStatus.FAILED,"
            + " a.failReason = :reason, a.completedAt = :completedAt"
            + " where a.analysisId = :analysisId"
            + " and a.status = com.team.docai.domain.analysis.entity.AnalysisStatus.ANALYZING"
            + " and (a.heartbeatAt < :cutoff or (a.heartbeatAt is null and a.startedAt < :cutoff))")
    int markFailedIfStale(@Param("analysisId") Long analysisId, @Param("cutoff") OffsetDateTime cutoff,
                          @Param("reason") AnalysisFailReason reason,
                          @Param("completedAt") OffsetDateTime completedAt);

    /**
     * 조건부 완료 전환. status 가 expected 일 때만 COMPLETED · DONE 으로 바꾼다.
     * 제외되지 않은 판정 중(JUDGING) 조항이 남아 있으면 바꾸지 않는다 (0행).
     * 반환값(바뀐 행 수)이 1이 아니면 최신 분석 연결·알림을 하지 않는다 (늦게 끝난 작업이 FAILED 를 덮어쓰지 않게).
     * 호출 뒤 영속성 컨텍스트를 비우므로, 같은 트랜잭션에서 미리 읽은 엔티티는 분리된다 (변경 감지 안 됨, 다시 읽을 것).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Analysis a set a.status = com.team.docai.domain.analysis.entity.AnalysisStatus.COMPLETED,"
            + " a.progressStep = com.team.docai.domain.analysis.entity.AnalysisStep.DONE,"
            + " a.completedAt = :completedAt"
            + " where a.analysisId = :analysisId and a.status = :expected"
            + " and not exists (select 1 from AnalysisClause c where c.analysisId = :analysisId"
            + " and c.excluded = false"
            + " and c.verdict = com.team.docai.domain.analysis.entity.ClauseVerdict.JUDGING)")
    int markCompleted(@Param("analysisId") Long analysisId, @Param("expected") AnalysisStatus expected,
                      @Param("completedAt") OffsetDateTime completedAt);

    /**
     * 조건부 실패 전환. status 가 expected 일 때만 FAILED 로 바꾼다. progress_step 은 마지막 단계를 그대로 둔다.
     * reason 은 필수 (ck_analysis_fail). 반환값이 1이 아니면 알림을 만들지 않는다.
     * 호출 뒤 영속성 컨텍스트를 비우므로, 같은 트랜잭션에서 미리 읽은 엔티티는 분리된다 (변경 감지 안 됨, 다시 읽을 것).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Analysis a set a.status = com.team.docai.domain.analysis.entity.AnalysisStatus.FAILED,"
            + " a.failReason = :reason, a.failPageNo = :failPageNo, a.detectedTypeName = :detectedTypeName,"
            + " a.completedAt = :completedAt"
            + " where a.analysisId = :analysisId and a.status = :expected")
    int markFailed(@Param("analysisId") Long analysisId, @Param("expected") AnalysisStatus expected,
                   @Param("reason") AnalysisFailReason reason, @Param("failPageNo") Integer failPageNo,
                   @Param("detectedTypeName") String detectedTypeName,
                   @Param("completedAt") OffsetDateTime completedAt);

    /**
     * 수정본 확정 ⑥: 그 버전 분석들의 비슷한 계약서 연결을 지운다.
     * B 의 확정 트랜잭션(⑥→⑦→⑧) 안에서 불리므로 영속성 컨텍스트를 비우지 않는다 (B 가 미리 읽은 엔티티의 변경을 지키기 위해).
     * 그래서 이 트랜잭션에서 이미 읽힌 Analysis 의 similarContractId 는 낡을 수 있다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Analysis a set a.similarContractId = null where a.versionId = :versionId")
    int clearSimilarContract(@Param("versionId") Long versionId);
}
