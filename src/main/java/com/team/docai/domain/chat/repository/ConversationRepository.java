package com.team.docai.domain.chat.repository;

import com.team.docai.domain.chat.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByConversationIdAndUserId(Long conversationId, UUID userId);

    /**
     * 대화 요약 조건부 갱신 (compare-and-set). 질문을 읽을 때 본 요약(expected)이 아직 그대로일 때만 새 요약으로 바꾼다.
     * 같은 대화에 동시에 온 두 질문이 각자 GPT 로 만든 요약을 차례로 덮어쓰면 먼저 끝난 쪽 내용이 사라지므로,
     * 뒤에 저장하는 쪽은 0행이 되어 요약을 쓰지 않는다 (version 칼럼 없이 같은 효과: Flyway V3 를 만들지 않기로 함).
     * expected 가 null 이면 요약이 아직 없을 때만. 한 문장 UPDATE 라 행 잠금은 이 문장 동안만 잡힌다.
     *
     * @return 바뀐 행 수 (0 이면 그 사이 다른 질문이 요약을 바꿈)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update conversation set summary = :summary, updated_at = :now"
            + " where conversation_id = :conversationId"
            + " and summary is not distinct from cast(:expected as text)", nativeQuery = true)
    int updateSummaryIfUnchanged(@Param("conversationId") Long conversationId, @Param("expected") String expected,
                                 @Param("summary") String summary, @Param("now") OffsetDateTime now);

    /** 요약은 그대로 두고 수정 시각만 (요약 갱신이 0행일 때 새 메시지가 생겼음을 남긴다) */
    @Modifying(flushAutomatically = true)
    @Query("update Conversation c set c.updatedAt = :now where c.conversationId = :conversationId")
    int touch(@Param("conversationId") Long conversationId, @Param("now") OffsetDateTime now);

    /**
     * 수정본 확정 ⑤: 임시 계약서에 연결된 대화를 이전 계약서로. B 의 확정 트랜잭션 안에서 불리므로 영속성 컨텍스트를 비우지 않는다
     * (B 가 미리 읽은 엔티티의 변경을 지키기 위해. AnalysisRepository.clearSimilarContract 와 같은 이유).
     */
    @Modifying(flushAutomatically = true)
    @Query("update Conversation c set c.contractId = :to, c.updatedAt = :now where c.contractId = :from")
    int moveContract(@Param("from") Long fromContractId, @Param("to") Long toContractId,
                     @Param("now") OffsetDateTime now);
}
