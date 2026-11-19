package com.team.docai.domain.chat.repository;

import com.team.docai.domain.chat.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 최근 메시지부터 (ID 내림차순). 질문·답변은 한 트랜잭션에서 차례로 저장하므로 ID 순서가 대화 순서다 */
    List<ChatMessage> findByConversationIdOrderByMessageIdDesc(Long conversationId, Pageable pageable);

    /** beforeMessageId 보다 앞의 메시지 (더 보기), 최근 것부터 */
    List<ChatMessage> findByConversationIdAndMessageIdLessThanOrderByMessageIdDesc(
            Long conversationId, Long beforeMessageId, Pageable pageable);
}
