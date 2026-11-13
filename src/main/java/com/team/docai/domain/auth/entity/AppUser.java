package com.team.docai.domain.auth.entity;

import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="app_user") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class AppUser extends BaseEntity {
    @Id private UUID id;
    @Column(nullable=false,unique=true,length=254) private String email;
    @Column(nullable=false) private String passwordHash;
    @Column(nullable=false,length=40) private String name;
    @Builder private AppUser(UUID id,String email,String passwordHash,String name) {this.id=id;this.email=email;this.passwordHash=passwordHash;this.name=name;}
    public static AppUser create(String email,String passwordHash,String name) {return AppUser.builder().id(UUID.randomUUID()).email(email).passwordHash(passwordHash).name(name).build();}
}
