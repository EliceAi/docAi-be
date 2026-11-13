package com.team.docai.web.chat.dto.request;

import com.team.docai.domain.chat.dto.ChatCommand;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record ChatRequest(@NotBlank(message="질문을 입력해 주세요") @Size(max=2000,message="질문은 2000자 이내입니다") String question,@NotNull(message="요청 식별자는 필수입니다") UUID requestKey){public ChatCommand toCommand(){return new ChatCommand(question,requestKey);}}
