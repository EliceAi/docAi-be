package com.team.docai.domain.law.repository;

import com.team.docai.domain.law.entity.IssueTag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueTagRepository extends JpaRepository<IssueTag, String> {
}
