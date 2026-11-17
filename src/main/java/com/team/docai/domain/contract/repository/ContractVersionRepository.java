package com.team.docai.domain.contract.repository;
import com.team.docai.domain.contract.entity.ContractVersion;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface ContractVersionRepository extends JpaRepository<ContractVersion,Long> {
 Optional<ContractVersion> findByContractIdAndVersionNo(Long contractId,int versionNo);
 @Modifying(clearAutomatically=true,flushAutomatically=true)
 @Query(value="UPDATE contract_version v SET latest_analysis_id=:analysisId,updated_at=now() FROM contract c WHERE v.version_id=:id AND c.contract_id=v.contract_id AND c.deleted_at IS NULL",nativeQuery=true)
 int link(@Param("id") Long id,@Param("analysisId") Long analysisId);
}
