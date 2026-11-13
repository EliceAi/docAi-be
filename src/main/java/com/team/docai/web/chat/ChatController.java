package com.team.docai.web.chat;

import com.team.docai.domain.chat.service.ChatService;
import com.team.docai.web.chat.dto.request.*;
import com.team.docai.web.chat.dto.response.ChatResponse;
import com.team.docai.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.UUID;
@RestController @RequestMapping("/api/chats") @RequiredArgsConstructor
public class ChatController {
    private final ChatService service;
    @PostMapping public ResponseEntity<ApiResponse<ChatResponse>> create(@AuthenticationPrincipal UUID user,@RequestBody CreateChatRequest r){return ResponseEntity.status(201).body(ApiResponse.success(201,ChatResponse.from(service.create(user,r.toCommand()))));}
    @GetMapping("/{id}") public ApiResponse<ChatResponse> get(@AuthenticationPrincipal UUID user,@PathVariable UUID id){return ApiResponse.success(ChatResponse.from(service.get(user,id)));}
    @PostMapping("/{id}/messages") public ApiResponse<ChatResponse> ask(@AuthenticationPrincipal UUID user,@PathVariable UUID id,@Valid @RequestBody ChatRequest r){return ApiResponse.success(ChatResponse.from(service.ask(user,id,r.toCommand())));}
}
