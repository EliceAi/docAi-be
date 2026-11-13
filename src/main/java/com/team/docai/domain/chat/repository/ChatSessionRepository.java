package com.team.docai.domain.chat.repository;

import com.team.docai.domain.chat.entity.ChatSession;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface ChatSessionRepository extends JpaRepository<ChatSession,UUID> {
    Optional<ChatSession> findByIdAndUserId(UUID id,UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from ChatSession c where c.id=:id and c.userId=:userId") Optional<ChatSession> lockOwned(UUID id,UUID userId);
}
