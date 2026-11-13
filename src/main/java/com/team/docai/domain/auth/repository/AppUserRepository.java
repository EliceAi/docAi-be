package com.team.docai.domain.auth.repository;

import com.team.docai.domain.auth.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AppUserRepository extends JpaRepository<AppUser,UUID> { Optional<AppUser> findByEmail(String email); boolean existsByEmail(String email); }
