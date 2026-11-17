package com.team.docai.domain.analysis.entity;

import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.dto.AiCallLogCommand;
import com.team.docai.global.util.TextTruncator;
import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "ai_call_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiCallLog extends BaseEntity {

    public static final int MODEL_MAX_LENGTH = 50;
    public static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long logId;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GptPurpose purpose;

    private Long analysisId;
    private Long chunkId;
    private Long comparisonId;
    private Long conversationId;

    @Column(nullable = false, length = MODEL_MAX_LENGTH)
    private String model;

    private Integer inputTokens;
    private Integer cachedInputTokens;
    private Integer outputTokens;
    private Integer latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiCallStatus status;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Builder
    private AiCallLog(UUID userId, GptPurpose purpose, Long analysisId, Long chunkId, Long comparisonId,
                      Long conversationId, String model, Integer inputTokens, Integer cachedInputTokens,
                      Integer outputTokens, Integer latencyMs, AiCallStatus status, String errorMessage) {
        this.userId = userId;
        this.purpose = purpose;
        this.analysisId = analysisId;
        this.chunkId = chunkId;
        this.comparisonId = comparisonId;
        this.conversationId = conversationId;
        this.model = model;
        this.inputTokens = inputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = latencyMs;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public static AiCallLog create(AiCallLogCommand command) {
        return AiCallLog.builder()
                .userId(command.userId())
                .purpose(command.purpose())
                .analysisId(command.analysisId())
                .chunkId(command.chunkId())
                .comparisonId(command.comparisonId())
                .conversationId(command.conversationId())
                .model(TextTruncator.truncate(command.model() == null ? "unknown" : command.model(), MODEL_MAX_LENGTH))
                .inputTokens(command.inputTokens())
                .cachedInputTokens(command.cachedInputTokens())
                .outputTokens(command.outputTokens())
                .latencyMs(command.latencyMs())
                .status(command.status())
                .errorMessage(TextTruncator.truncate(command.errorMessage(), ERROR_MESSAGE_MAX_LENGTH))
                .build();
    }
}
