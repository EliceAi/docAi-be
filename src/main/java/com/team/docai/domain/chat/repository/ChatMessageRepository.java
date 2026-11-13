package com.team.docai.domain.chat.repository;

import com.team.docai.domain.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ChatMessageRepository extends JpaRepository<ChatMessage,Long>{List<ChatMessage> findBySessionIdOrderByIdAsc(UUID sessionId);Optional<ChatMessage> findBySessionIdAndRequestKey(UUID sessionId,UUID requestKey);}
