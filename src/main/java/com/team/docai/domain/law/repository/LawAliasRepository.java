package com.team.docai.domain.law.repository;

import com.team.docai.domain.law.entity.LawAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LawAliasRepository extends JpaRepository<LawAlias, String> {

    List<LawAlias> findByLawId(Long lawId);
}
