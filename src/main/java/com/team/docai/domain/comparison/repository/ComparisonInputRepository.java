package com.team.docai.domain.comparison.repository;

import com.team.docai.domain.comparison.entity.ComparisonInput;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ComparisonInputRepository extends JpaRepository<ComparisonInput,UUID>{List<ComparisonInput> findByUserIdAndPreviousIdAndCurrentId(UUID userId,UUID previousId,UUID currentId);}
