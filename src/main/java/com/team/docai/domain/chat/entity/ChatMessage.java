package com.team.docai.domain.chat.entity;

import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="chat_message",uniqueConstraints=@UniqueConstraint(columnNames={"session_id","request_key"})) @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ChatMessage extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private UUID sessionId;
    @Column(nullable=false) private UUID requestKey;
    @Column(nullable=false,columnDefinition="text") private String question;
    @Column(nullable=false,columnDefinition="text") private String answer;
    @Builder private ChatMessage(UUID sessionId,UUID requestKey,String question,String answer){this.sessionId=sessionId;this.requestKey=requestKey;this.question=question;this.answer=answer;}
    public static ChatMessage create(UUID session,UUID key,String question,String answer){return ChatMessage.builder().sessionId(session).requestKey(key).question(question).answer(answer).build();}
}
