package com.team.docai.domain.chat.dto;

import java.util.*;
public record ChatInfo(UUID id,UUID documentId,List<Message> messages){public record Message(Long id,String question,String answer){}}
