package com.team.docai.web.chat;

import com.team.docai.domain.chat.dto.ChatAnswerInfo;
import com.team.docai.domain.chat.service.ChatPipeline;
import com.team.docai.domain.chat.service.ChatService;
import com.team.docai.global.common.ApiResponse;
import com.team.docai.web.chat.dto.request.AskRequest;
import com.team.docai.web.chat.dto.response.AskResponse;
import com.team.docai.web.chat.dto.response.ChatHistoryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 챗봇: AI 질문, 대화 기록 조회. 둘 다 인증 필요 (명세 Authorization 필수) */
@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatPipeline chatPipeline;
    private final ChatService chatService;

    /** 새 대화를 만들면 201, 기존 대화에 이어서 질문하면 200 (명세 비고) */
    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<AskResponse>> ask(
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody AskRequest request) {
        ChatAnswerInfo info = chatPipeline.ask(userId, request.toCommand());
        HttpStatus status = info.newConversation() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(status.value(), AskResponse.from(info)));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<ApiResponse<ChatHistoryResponse>> messages(
            @AuthenticationPrincipal UUID userId, @PathVariable Long conversationId,
            @RequestParam(required = false) Long beforeMessageId, @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.success(
                ChatHistoryResponse.from(chatService.getMessages(userId, conversationId, beforeMessageId, size))));
    }
}
