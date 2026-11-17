package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AnalysisRequestInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 분석 요청 (POST /api/contracts/{contractId}/analyses).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisRequestService {

    static final String RUNNING_UNIQUE_INDEX = "uq_analysis_running";

    private final AnalysisRequestRateLimiter rateLimiter;
    private final AnalysisRequestWriter requestWriter;

    /**
     * 횟수 제한을 확인한 뒤 요청 트랜잭션(AnalysisRequestWriter)을 부른다.
     * 같은 버전에 ANALYZING 이 동시에 둘 생기려 하면 uq_analysis_running 이 하나를 막는다 (계약서 행 잠금이 있어 보통은
     * 일어나지 않음). 막힌 트랜잭션은 이미 롤백됐으므로 새 트랜잭션으로 한 번 더 불러 먼저 커밋된 분석을 돌려준다.
     * 그래서 이 메서드는 바깥 트랜잭션 없이 불러야 한다 (NEVER).
     */
    @Transactional(propagation = Propagation.NEVER)
    public AnalysisRequestInfo request(UUID userId, Long contractId) {
        rateLimiter.check(userId);
        try {
            return requestWriter.createOrGetRunning(userId, contractId);
        } catch (DataIntegrityViolationException e) {
            if (!isRunningDuplicate(e)) {
                throw e;
            }
            log.info("같은 버전의 분석이 동시에 만들어져 먼저 만든 분석을 돌려줌: contractId={}", contractId);
            return requestWriter.createOrGetRunning(userId, contractId);
        }
    }

    /**
     * 위반 제약 이름이 uq_analysis_running 인지 (메시지 글자는 보지 않는다).
     * 원인 사슬에서 PostgreSQL 드라이버 예외가 서버에서 받은 제약 이름을 먼저 본다. 드라이버는 runtimeOnly 라
     * 컴파일 의존 없이 리플렉션으로 {@code getServerErrorMessage().getConstraint()} 를 부른다.
     * 드라이버 값이 없으면 Hibernate 가 알려 준 제약 이름을 쓴다.
     */
    static boolean isRunningDuplicate(Throwable e) {
        return RUNNING_UNIQUE_INDEX.equalsIgnoreCase(violatedConstraint(e));
    }

    static String violatedConstraint(Throwable e) {
        String hibernateName = null;
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String serverName = serverConstraint(t);
            if (serverName != null) {
                return serverName;
            }
            if (hibernateName == null && t instanceof ConstraintViolationException cve) {
                hibernateName = cve.getConstraintName();
            }
        }
        return hibernateName;
    }

    /** SQLException 에 getServerErrorMessage() 가 있으면 (PSQLException) 그 제약 이름. 없거나 못 읽으면 null */
    private static String serverConstraint(Throwable t) {
        if (!(t instanceof SQLException)) {
            return null;
        }
        try {
            Method getMessage = t.getClass().getMethod("getServerErrorMessage");
            Object serverMessage = getMessage.invoke(t);
            if (serverMessage == null) {
                return null;
            }
            Object constraint = serverMessage.getClass().getMethod("getConstraint").invoke(serverMessage);
            return constraint instanceof String name ? name : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }
}
