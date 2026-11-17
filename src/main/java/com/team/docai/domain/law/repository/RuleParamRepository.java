package com.team.docai.domain.law.repository;

import com.team.docai.domain.law.entity.RuleParam;
import com.team.docai.domain.law.entity.RuleParamId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleParamRepository extends JpaRepository<RuleParam, RuleParamId> {
}
