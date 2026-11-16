package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.AiCallLogCommand;
import com.team.docai.domain.analysis.entity.AiCallLog;
import com.team.docai.domain.analysis.repository.AiCallLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ai_call_log 저장. 호출한 쪽 트랜잭션이 롤백돼도 기록이 남도록 별도 트랜잭션(REQUIRES_NEW)으로 저장한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiCallLogWriter {

    private final AiCallLogRepository aiCallLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AiCallLogCommand command) {
        aiCallLogRepository.save(AiCallLog.create(command));
    }
}
