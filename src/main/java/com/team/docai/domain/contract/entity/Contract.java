package com.team.docai.domain.contract.entity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
import java.time.OffsetDateTime;
import com.team.docai.global.common.BaseEntity;
@Entity @Table(name="contract") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Contract extends BaseEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long contractId;
 private UUID userId; private String documentTypeCode; private String title; private boolean archived; private int currentVersionNo; private OffsetDateTime archivedAt; private OffsetDateTime deletedAt;
 @Builder(access=AccessLevel.PRIVATE) private Contract(UUID userId,String documentTypeCode){this.userId=userId;this.documentTypeCode=documentTypeCode;this.currentVersionNo=1;}
 public static Contract create(UUID userId,String documentTypeCode){return new Contract(userId,documentTypeCode);}
}
