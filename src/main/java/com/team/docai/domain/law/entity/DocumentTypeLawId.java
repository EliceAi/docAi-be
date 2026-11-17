package com.team.docai.domain.law.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** document_type_law 의 복합 기본 키 (document_type_code, law_id). */
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DocumentTypeLawId implements Serializable {

    private String documentTypeCode;
    private Long lawId;
}
