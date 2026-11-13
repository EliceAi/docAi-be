package com.team.docai.web.chat.dto.request;

import java.util.UUID;
public record CreateChatRequest(UUID documentId){public UUID toCommand(){return documentId;}}
