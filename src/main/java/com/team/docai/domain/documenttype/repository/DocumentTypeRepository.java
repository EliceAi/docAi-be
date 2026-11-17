package com.team.docai.domain.documenttype.repository;
import com.team.docai.domain.documenttype.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface DocumentTypeRepository extends JpaRepository<DocumentType,String> {
 List<DocumentType> findAllByOrderBySortOrderAscCodeAsc();
}
