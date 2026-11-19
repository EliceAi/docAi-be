package com.team.docai.domain.chat.entity;

import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 대화 (conversation). 연결된 계약서(contractId)는 대화 중에 바꾸지 않는다. 바뀌는 곳은 수정본 확정 때
 * {@code ChatService.moveContract} 뿐이다 (임시 계약서 → 이전 계약서).
 * <p>
 * summary(오래된 대화 요약)는 동시에 온 두 질문이 서로 덮어쓰지 않게 엔티티 변경 감지가 아니라
 * 조건부 UPDATE(ConversationRepository.updateSummaryIfUnchanged)로만 바꾼다. version 칼럼은 없다 (Flyway V3 를 만들지 않기로 함).
 */
@Entity
@Table(name = "conversation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long conversationId;

    @Column(nullable = false)
    private UUID userId;

    private Long contractId;
    private Long analysisId;
    private Long comparisonId;

    @Column(columnDefinition = "text")
    private String summary;

    @Builder
    private Conversation(UUID userId, Long contractId, Long analysisId, Long comparisonId, String summary) {
        this.userId = userId;
        this.contractId = contractId;
        this.analysisId = analysisId;
        this.comparisonId = comparisonId;
        this.summary = summary;
    }

    /** 새 대화. 일반 질문이면 contractId·analysisId·comparisonId 모두 null */
    public static Conversation create(UUID userId, Long contractId, Long analysisId, Long comparisonId, String summary) {
        return Conversation.builder()
                .userId(userId)
                .contractId(contractId)
                .analysisId(analysisId)
                .comparisonId(comparisonId)
                .summary(summary)
                .build();
    }
}
