package com.team.docai.domain.law.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 문서별 관련 법령 (document_type_law). document_type 은 B 소유라 코드 값만 가진다. */
@Entity
@Table(name = "document_type_law")
@IdClass(DocumentTypeLawId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentTypeLaw {

    @Id
    @Column(nullable = false, length = 40)
    private String documentTypeCode;

    @Id
    @Column(nullable = false)
    private Long lawId;

    @Column(nullable = false)
    private Boolean core;

    @Builder
    private DocumentTypeLaw(String documentTypeCode, Long lawId, Boolean core) {
        this.documentTypeCode = documentTypeCode;
        this.lawId = lawId;
        this.core = core;
    }

    public static DocumentTypeLaw create(String documentTypeCode, Long lawId, boolean core) {
        return DocumentTypeLaw.builder().documentTypeCode(documentTypeCode).lawId(lawId).core(core).build();
    }
}
