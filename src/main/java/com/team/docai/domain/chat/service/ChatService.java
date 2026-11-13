package com.team.docai.domain.chat.service;

import com.team.docai.domain.chat.dto.*;
import com.team.docai.domain.chat.entity.*;
import com.team.docai.domain.chat.repository.*;
import com.team.docai.domain.document.service.DocumentService;
import com.team.docai.domain.document.client.DocumentAiClient;
import com.team.docai.global.error.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ChatService {
    private final ChatSessionRepository sessions;private final ChatMessageRepository messages;private final DocumentService documents;private final DocumentAiClient ai;
    @Transactional public ChatInfo create(UUID user,UUID document){if(document!=null)documents.get(user,document);return info(sessions.save(ChatSession.create(user,document)));}
    public ChatInfo get(UUID user,UUID id){return info(sessions.findByIdAndUserId(id,user).orElseThrow(()->new BusinessException(ErrorCode.CHAT_NOT_FOUND)));}
    @Transactional public ChatInfo ask(UUID user,UUID id,ChatCommand c){
        var s=sessions.lockOwned(id,user).orElseThrow(()->new BusinessException(ErrorCode.CHAT_NOT_FOUND));
        if(messages.findBySessionIdAndRequestKey(id,c.requestKey()).isPresent())return info(s);
        String context="";boolean pending=false;
        if(s.getDocumentId()!=null){var d=documents.get(user,s.getDocumentId());pending=d.analysis()==null;context=d.analysis()==null?"":d.analysis().toString();}
        String answer=pending?"아직 분석이 완료되지 않아 문서 내용으로 답할 수 없어요. 분석 완료 후 다시 질문해 주세요.":ai.answer(context,c.question());
        messages.saveAndFlush(ChatMessage.create(id,c.requestKey(),c.question(),answer));return info(s);
    }
    private ChatInfo info(ChatSession s){return new ChatInfo(s.getId(),s.getDocumentId(),messages.findBySessionIdOrderByIdAsc(s.getId()).stream().map(m->new ChatInfo.Message(m.getId(),m.getQuestion(),m.getAnswer())).toList());}
}
