package com.team.docai.domain.contract.repository;
import com.team.docai.domain.contract.entity.ContractPage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ContractPageRepository extends JpaRepository<ContractPage,Long> {
 List<ContractPage> findByVersionIdOrderByPageNoAsc(Long id);
}
