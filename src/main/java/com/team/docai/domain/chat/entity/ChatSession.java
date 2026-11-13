package com.team.docai.domain.chat.entity;

import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="chat_session") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ChatSession extends BaseEntity {
    @Id private UUID id;@Column(nullable=false) private UUID userId;private UUID documentId;
    @Builder private ChatSession(UUID id,UUID userId,UUID documentId){this.id=id;this.userId=userId;this.documentId=documentId;}
    public static ChatSession create(UUID user,UUID doc){return ChatSession.builder().id(UUID.randomUUID()).userId(user).documentId(doc).build();}
}
