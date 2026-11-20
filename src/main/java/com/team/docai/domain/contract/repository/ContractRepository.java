package com.team.docai.domain.contract.repository;
import com.team.docai.domain.contract.entity.Contract;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.*;
public interface ContractRepository extends JpaRepository<Contract,Long> {
 Optional<Contract> findByContractIdAndUserIdAndDeletedAtIsNull(Long id,UUID userId);
 List<Contract> findByUserIdAndArchivedTrueAndDeletedAtIsNullOrderByUpdatedAtDescContractIdDesc(UUID userId);
 List<Contract> findByUserIdAndDocumentTypeCodeAndArchivedTrueAndDeletedAtIsNullOrderByContractIdDesc(UUID userId,String documentTypeCode);
 List<Contract> findByArchivedFalseAndDeletedAtIsNullAndCreatedAtBefore(OffsetDateTime cutoff);
 List<Contract> findByDeletedAtIsNotNull();
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select c from Contract c where c.contractId=:id and c.deletedAt is null")
 Optional<Contract> lockActive(@Param("id") Long id);
 @Modifying(clearAutomatically=true,flushAutomatically=true)
 @Query("update Contract c set c.title=:title, c.updatedAt=CURRENT_TIMESTAMP where c.contractId=:id and c.deletedAt is null and (c.title is null or c.title='')")
 int setTitleIfEmpty(@Param("id") Long id,@Param("title") String title);
}
