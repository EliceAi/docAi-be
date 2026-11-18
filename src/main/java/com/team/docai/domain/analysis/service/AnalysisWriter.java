package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.global.util.TextTruncator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 백그라운드 분석의 저장. 메서드마다 짧은 트랜잭션 하나 (GPT 를 기다리는 동안 DB 연결·잠금을 쥐지 않게).
 * 상태 전환은 모두 조건부 UPDATE(WHERE status = 'ANALYZING')이고, 1행이 바뀐 같은 트랜잭션에서만
 * 최신 분석 연결·알림을 한다. 늦게 끝난 작업이 이미 FAILED 인 분석을 덮어쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisWriter {

    /** B 의 NotificationService.createAnalysisResult 가 받는 알림 종류 (B_연결메서드_반환값.md 7번) */
    static final String ANALYSIS_DONE = "ANALYSIS_DONE";
    static final String ANALYSIS_FAILED = "ANALYSIS_FAILED";
    static final String DONE_TITLE = "분석이 끝났어요";
    static final String FAILED_TITLE = "분석하지 못했어요";
    /** notification.sub_title 칸 길이 */
    static final int SUB_TITLE_MAX_LENGTH = 100;
    static final List<ClauseVerdict> NEEDS_CHECK_VERDICTS = List.of(ClauseVerdict.NEEDS_CHECK, ClauseVerdict.UNCERTAIN);

    private final AnalysisRepository analysisRepository;
    private final ContractService contractService;
    private final NotificationService notificationService;
    private final AnalysisClauseRepository clauseRepository;
    private final ObjectMapper objectMapper;

    /**
     * 단계 시작: 진행 단계와 heartbeat 를 저장한다.
     *
     * @return 아직 ANALYZING 이면 true. false 면 다른 곳이 이미 끝낸 분석이므로 파이프라인을 멈춘다
     */
    @Transactional
    public boolean advance(Long analysisId, AnalysisStep step) {
        return analysisRepository.advance(analysisId, step, OffsetDateTime.now()) == 1;
    }

    /**
     * ANALYZING → COMPLETED · DONE. 제외되지 않은 판정 중(JUDGING) 조항이 남아 있으면 바꾸지 않는다.
     * 1행 바뀌면 같은 트랜잭션에서 최신 분석 연결(B3, B 쪽 MANDATORY)과 완료 알림(B6).
     * 그 사이 계약서가 삭제되면 B3 이 CONTRACT_NOT_FOUND 를 던져 전체가 롤백되고, 파이프라인이 FAILED 로 바꾼다.
     * <p>
     * 잠금 순서 예외: 공통 규칙은 계약서 → 비교 → 분석인데, 여기서는 분석 행(markCompleted 의 UPDATE)을 먼저 잠그고
     * 그다음 계약서 버전 행(B3 의 UPDATE contract_version)을 잠근다. 계약서 행(contract)은 잠그지 않는다.
     * 계약서 쪽 트랜잭션(종류 변경·재업로드·보관)은 분석 중이면 409 로 끝나 분석 행을 잠그지 않으므로 서로 반대 순서로
     * 기다리는 일이 없다. 이 메서드에 계약서 행을 잠그는 호출을 더하지 않는다.
     * <p>
     * 완료 알림 부제 (알림 목록 명세 예시 형식, 2026-11-18 사용자 결정): 확인 필요(제외되지 않은 NEEDS_CHECK + UNCERTAIN 조항 수)가
     * 1 이상이면 "{계약서 이름} · 확인 필요 N", 0 이면 "{계약서 이름} · 확인할 조항 없음". 이름은 이 분석이 정한 이름
     * ({@link AnalysisTitle}: 1차 문서 제목, 없으면 문서 종류 이름)이고 B 테이블은 읽지 않는다.
     * <p>
     * 계약서 이름(B4 {@code ContractService.setTitleIfEmpty})은 이 메서드 안에서 부르지 않는다.
     * B4 는 contract 행을 FOR UPDATE 로 잠그므로 분석 → 계약서 순서가 되어, 계약서 → 분석 순서로 잠그는 분석 요청과
     * 데드락이 날 수 있다. 파이프라인이 이 메서드가 true 를 돌려준 뒤(완료 트랜잭션이 끝난 뒤) 따로 부른다.
     *
     * @return 바뀌었으면 true
     */
    @Transactional
    public boolean complete(AnalysisRunContext ctx) {
        int changed = analysisRepository.markCompleted(ctx.analysisId(), AnalysisStatus.ANALYZING, OffsetDateTime.now());
        if (changed != 1) {
            return false;
        }
        contractService.linkLatestAnalysis(ctx.versionId(), ctx.analysisId());
        long needsCheck = clauseRepository.countByAnalysisIdAndExcludedFalseAndVerdictIn(ctx.analysisId(),
                NEEDS_CHECK_VERDICTS);
        notificationService.createAnalysisResult(ctx.userId(), ctx.analysisId(), ANALYSIS_DONE,
                DONE_TITLE, doneSubTitle(title(ctx.analysisId()), needsCheck));
        return true;
    }

    /** 이 분석이 정한 계약서 이름 ({@link AnalysisTitle}). 분석이 없으면 null */
    public String title(Long analysisId) {
        return analysisRepository.findById(analysisId)
                .map(a -> AnalysisTitle.resolve(objectMapper, a.getExtractionJson(), a.getDocumentTypeCode()))
                .orElse(null);
    }

    /**
     * "{계약서 이름} · 확인 필요 N" (0 이면 "{계약서 이름} · 확인할 조항 없음"). 칸(100자)을 넘으면 이름을 줄인다.
     * 이름이 없으면 뒷부분만.
     */
    static String doneSubTitle(String contractTitle, long needsCheck) {
        String count = needsCheck > 0 ? "확인 필요 " + needsCheck : "확인할 조항 없음";
        if (contractTitle == null || contractTitle.isBlank()) {
            return count;
        }
        String suffix = " · " + count;
        int room = SUB_TITLE_MAX_LENGTH - suffix.codePointCount(0, suffix.length());
        return TextTruncator.truncate(contractTitle.trim(), Math.max(0, room)) + suffix;
    }

    /**
     * ANALYZING → FAILED. 진행 단계는 마지막 단계 그대로. 1행 바뀌면 같은 트랜잭션에서 실패 알림(B6).
     * latest_analysis_id 는 건드리지 않는다 (재분석이 실패해도 이전 완료 결과가 보임).
     *
     * @return 바뀌었으면 true
     */
    @Transactional
    public boolean fail(AnalysisRunContext ctx, AnalysisFailReason reason, Integer failPageNo, String detectedTypeName) {
        return doFail(ctx, reason, failPageNo, detectedTypeName);
    }

    /**
     * {@link #fail} 과 같지만 항상 새 트랜잭션으로 한다. 커밋이 끝난 트랜잭션의 AFTER_COMMIT 리스너에서 부를 때 쓴다
     * (그 안에서 REQUIRED 로 부르면 이미 끝난 트랜잭션에 참여해 저장이 커밋되지 않는다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failInNewTransaction(AnalysisRunContext ctx, AnalysisFailReason reason) {
        return doFail(ctx, reason, null, null);
    }

    private boolean doFail(AnalysisRunContext ctx, AnalysisFailReason reason, Integer failPageNo, String detectedTypeName) {
        int changed = analysisRepository.markFailed(ctx.analysisId(), AnalysisStatus.ANALYZING, reason, failPageNo,
                TextTruncator.truncate(detectedTypeName, Analysis.DETECTED_TYPE_NAME_MAX_LENGTH),
                OffsetDateTime.now());
        if (changed != 1) {
            return false;
        }
        notificationService.createAnalysisResult(ctx.userId(), ctx.analysisId(), ANALYSIS_FAILED,
                FAILED_TITLE, failedSubTitle(reason));
        return true;
    }

    /** 이 서버가 돌리는 분석의 heartbeat_at = now (ANALYZING 인 것만). 바뀐 행 수를 돌려준다. */
    @Transactional
    public int touchHeartbeat(Collection<Long> analysisIds) {
        if (analysisIds.isEmpty()) {
            return 0;
        }
        return analysisRepository.touchHeartbeat(analysisIds, OffsetDateTime.now());
    }

    /** heartbeat 가 cutoff 전에 멈춘 ANALYZING 분석 ID */
    public List<Long> findStaleIds(OffsetDateTime cutoff) {
        return analysisRepository.findStaleAnalyzingIds(cutoff);
    }

    /**
     * 멈춘 분석 하나를 FAILED · TEMPORARY_ERROR 로 (아직 멈춰 있을 때만).
     * 실패 알림은 만들지 못한다: 알림 받을 사용자(userId)를 버전에서 찾을 B 메서드(versionId → userId)가 없다.
     * B 에 그 메서드가 생기면 여기서 실패 알림(B6)을 만든다.
     *
     * @return 바뀌었으면 true
     */
    @Transactional
    public boolean failStale(Long analysisId, OffsetDateTime cutoff) {
        int changed = analysisRepository.markFailedIfStale(analysisId, cutoff, AnalysisFailReason.TEMPORARY_ERROR,
                OffsetDateTime.now());
        if (changed == 1) {
            log.warn("heartbeat 가 멈춘 분석을 실패로 바꿈: analysisId={}", analysisId);
        }
        return changed == 1;
    }

    private static String failedSubTitle(AnalysisFailReason reason) {
        return switch (reason) {
            case TYPE_MISMATCH -> "고른 문서 종류와 달라 보여요";
            case PAGE_UNREADABLE -> "읽지 못한 쪽이 있어요";
            case TEMPORARY_ERROR -> "잠시 문제가 생겼어요. 다시 시도해 주세요";
        };
    }
}
