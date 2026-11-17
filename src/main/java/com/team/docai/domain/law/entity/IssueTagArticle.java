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

/** 꼬리표-조문 매핑 (issue_tag_article). 근거 찾기 1순위 (아키텍처 6단계). */
@Entity
@Table(name = "issue_tag_article")
@IdClass(IssueTagArticleId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueTagArticle {

    @Id
    @Column(nullable = false, length = 40)
    private String issueTagCode;

    @Id
    @Column(nullable = false)
    private Long lawArticleId;

    @Column(nullable = false)
    private Integer priority;

    @Builder
    private IssueTagArticle(String issueTagCode, Long lawArticleId, Integer priority) {
        this.issueTagCode = issueTagCode;
        this.lawArticleId = lawArticleId;
        this.priority = priority;
    }

    public static IssueTagArticle create(String issueTagCode, Long lawArticleId, int priority) {
        return IssueTagArticle.builder().issueTagCode(issueTagCode).lawArticleId(lawArticleId).priority(priority).build();
    }
}
