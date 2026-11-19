package com.team.docai.domain.chat.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * 대화 메시지 (chat_message). 질문(USER)과 답변(ASSISTANT)을 한 트랜잭션에서 함께 저장한다
 * (GPT 응답에 실패하면 둘 다 저장하지 않는다. 명세: "작성한 질문은 앱에 그대로 둠").
 */
@Entity
@Table(name = "chat_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage extends BaseEntity {

    public static final int SCREEN_MAX_LENGTH = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long messageId;

    @Column(nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatRole role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = SCREEN_MAX_LENGTH)
    private String screen;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AnswerType answerType;

    @JdbcTypeCode(SqlTypes.JSON)
    private ChatRefs refsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    private ChatPartial partialJson;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ChatAction> actionsJson;

    @Builder
    private ChatMessage(Long conversationId, ChatRole role, String content, String screen, AnswerType answerType,
                        ChatRefs refsJson, ChatPartial partialJson, List<ChatAction> actionsJson) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.screen = screen;
        this.answerType = answerType;
        this.refsJson = refsJson;
        this.partialJson = partialJson;
        this.actionsJson = actionsJson;
    }

    /** 사용자 질문. 보고 있던 조항이 있으면 refs_json 에 clauseId 로 남긴다 */
    public static ChatMessage question(Long conversationId, String content, String screen, Long clauseId) {
        return ChatMessage.builder()
                .conversationId(conversationId)
                .role(ChatRole.USER)
                .content(content)
                .screen(screen)
                .refsJson(clauseId == null ? null : ChatRefs.question(clauseId))
                .build();
    }

    /** AI 답변. partial 은 PARTIAL 일 때만 */
    public static ChatMessage answer(Long conversationId, String content, AnswerType answerType,
                                     List<ChatReference> references, ChatPartial partial, List<ChatAction> actions) {
        return ChatMessage.builder()
                .conversationId(conversationId)
                .role(ChatRole.ASSISTANT)
                .content(content)
                .answerType(answerType)
                .refsJson(ChatRefs.answer(references))
                .partialJson(partial)
                .actionsJson(List.copyOf(actions))
                .build();
    }

    /** 답변의 참고한 정보 (질문이면 빈 목록) */
    public List<ChatReference> references() {
        return refsJson == null || refsJson.references() == null ? List.of() : refsJson.references();
    }

    public List<ChatAction> actions() {
        return actionsJson == null ? List.of() : actionsJson;
    }
}
