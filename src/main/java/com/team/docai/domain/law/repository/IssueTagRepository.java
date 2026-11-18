package com.team.docai.domain.law.repository;

import com.team.docai.domain.law.entity.IssueTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueTagRepository extends JpaRepository<IssueTag, String> {

    /** 문서 종류의 꼬리표 (코드 순) */
    List<IssueTag> findByDocumentTypeCodeOrderByCodeAsc(String documentTypeCode);
}
