package com.team.docai.web.chat.dto.response;

import com.team.docai.domain.chat.dto.ChatInfo;
import java.util.*;
public record ChatResponse(UUID id,UUID documentId,List<ChatInfo.Message> messages){public static ChatResponse from(ChatInfo i){return new ChatResponse(i.id(),i.documentId(),i.messages());}}
