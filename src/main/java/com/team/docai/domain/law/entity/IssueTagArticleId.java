package com.team.docai.domain.law.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** issue_tag_article 의 복합 기본 키 (issue_tag_code, law_article_id). */
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IssueTagArticleId implements Serializable {

    private String issueTagCode;
    private Long lawArticleId;
}
