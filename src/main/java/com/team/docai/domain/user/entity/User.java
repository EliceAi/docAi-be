package com.team.docai.domain.user.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="users") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class User {
 @Id private UUID userId;
 @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
 @Column(nullable=false,unique=true,length=64,columnDefinition="char(64)") private String deviceIdHash;
 @Column(nullable=false,updatable=false) private OffsetDateTime createdAt;
 @Builder(access=AccessLevel.PRIVATE)
 private User(UUID userId,String deviceIdHash,OffsetDateTime createdAt){this.userId=userId;this.deviceIdHash=deviceIdHash;this.createdAt=createdAt;}
 public static User create(String hash){return User.builder().userId(UUID.randomUUID()).deviceIdHash(hash).createdAt(OffsetDateTime.now()).build();}
}
