package com.team.docai.domain.schedule.repository;

import com.team.docai.domain.schedule.entity.ContractSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ContractScheduleRepository extends JpaRepository<ContractSchedule,UUID> {
    List<ContractSchedule> findByUserIdOrderByDateAsc(UUID userId);
    List<ContractSchedule> findByDocumentIdAndUserIdOrderByDateAsc(UUID documentId,UUID userId);
    void deleteByDocumentIdAndUserId(UUID documentId,UUID userId);
}
