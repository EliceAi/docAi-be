package com.team.docai.domain.law.repository;

import com.team.docai.domain.law.entity.IssueTagArticle;
import com.team.docai.domain.law.entity.IssueTagArticleId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueTagArticleRepository extends JpaRepository<IssueTagArticle, IssueTagArticleId> {

    List<IssueTagArticle> findByIssueTagCodeOrderByPriorityAsc(String issueTagCode);
}
