package com.team.docai.domain.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 챗봇. 지금은 B 가 부르는 연결 메서드(A7)만 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    /**
     * A7 수정본 확정 ⑤. 임시 계약서(fromContractId)에 연결된 대화를 이전 계약서(toContractId)로 옮긴다.
     * 지금은 아무것도 하지 않는다: 대화(conversation)를 만드는 곳은 AI 질문 API 뿐인데 아직 없으므로
     * 옮길 대화가 생기지 않는다. 챗봇 작업에서 Conversation 엔티티와 함께 실제로 구현한다.
     * B 의 확정 트랜잭션 안에서만 부른다 (없으면 예외).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveContract(Long fromContractId, Long toContractId) {
        // 챗봇 작업 전까지 옮길 대화가 없음 (위 설명)
    }
}
