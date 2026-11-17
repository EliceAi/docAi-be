package com.team.docai.domain.law.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 문제 꼬리표 (issue_tag). */
@Entity
@Table(name = "issue_tag")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueTag {

    @Id
    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 40)
    private String documentTypeCode;

    @Column(nullable = false, length = 50)
    private String name;

    @Builder
    private IssueTag(String code, String documentTypeCode, String name) {
        this.code = code;
        this.documentTypeCode = documentTypeCode;
        this.name = name;
    }

    public static IssueTag create(String code, String documentTypeCode, String name) {
        return IssueTag.builder().code(code).documentTypeCode(documentTypeCode).name(name).build();
    }
}
