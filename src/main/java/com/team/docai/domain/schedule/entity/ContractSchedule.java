package com.team.docai.domain.schedule.entity;

import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.UUID;
@Entity @Table(name="contract_schedule",uniqueConstraints=@UniqueConstraint(columnNames={"document_id","suggestion_key"})) @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ContractSchedule extends BaseEntity {
    @Id private UUID id;
    @Column(nullable=false) private UUID userId;
    @Column(nullable=false) private UUID documentId;
    @Column(nullable=false,length=80) private String suggestionKey;
    @Column(nullable=false,length=100) private String title;
    @Column(nullable=false) private LocalDate date;
    @Column(nullable=false,length=40) private String source;
    @Builder private ContractSchedule(UUID id,UUID userId,UUID documentId,String suggestionKey,String title,LocalDate date,String source){this.id=id;this.userId=userId;this.documentId=documentId;this.suggestionKey=suggestionKey;this.title=title;this.date=date;this.source=source;}
    public static ContractSchedule create(UUID user,UUID doc,String key,String title,LocalDate date,String source){return ContractSchedule.builder().id(UUID.randomUUID()).userId(user).documentId(doc).suggestionKey(key).title(title).date(date).source(source).build();}
}
