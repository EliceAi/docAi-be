package com.team.docai.domain.law.repository;

import com.team.docai.domain.law.entity.DocumentTypeLaw;
import com.team.docai.domain.law.entity.DocumentTypeLawId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentTypeLawRepository extends JpaRepository<DocumentTypeLaw, DocumentTypeLawId> {

    List<DocumentTypeLaw> findByDocumentTypeCodeOrderByCoreDescLawIdAsc(String documentTypeCode);
}
