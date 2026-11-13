package com.team.docai.domain.chat.dto;

import java.util.UUID;
public record ChatCommand(String question,UUID requestKey){}
