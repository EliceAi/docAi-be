package com.team.docai.domain.law.repository;

import com.team.docai.domain.law.entity.Law;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LawRepository extends JpaRepository<Law, Long> {

    Optional<Law> findByNameNorm(String nameNorm);

    Optional<Law> findBySourceLawId(String sourceLawId);
}
