package com.team.docai.domain.chat.service;

import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.chat.dto.AskCommand;
import com.team.docai.domain.chat.dto.ChatAnswerInfo;
import com.team.docai.domain.chat.dto.ChatGptResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * AI 질문 흐름 (아키텍처 "AI 질문(챗봇)도 같은 원리" 1~4단계). GPT 를 부르므로 트랜잭션을 붙이지 않는다
 * (CLAUDE.md Service 규칙의 예외: 응답을 기다리는 동안 DB 연결·행 잠금을 쥐지 않기 위해).
 * <ol>
 *   <li>횟수 제한 (RATE_LIMITED)</li>
 *   <li>확인과 맥락 모으기: 읽기 트랜잭션 하나 ({@link ChatContextReader})</li>
 *   <li>GPT(CHAT, 이미지 없음, json_schema strict). 실패하면 AI_REQUEST_FAILED / AI_TIMEOUT 이 그대로 나가고 아무것도 저장하지 않는다</li>
 *   <li>검증 ({@link ChatAnswerValidator}): 준 근거만 인용, 근거 부족이면 PARTIAL, 단정 표현·내부 키 지움, 이동 버튼은 서버가 만듦</li>
 *   <li>저장: 짧은 쓰기 트랜잭션 ({@link ChatWriter})</li>
 * </ol>
 * 대화만으로 문서를 보내거나 일정을 저장하지 않는다. 이동은 actions 버튼으로만.
 * <p>
 * 알려진 한계: 새 대화의 첫 질문은 대화 행을 GPT 응답 뒤(저장 트랜잭션)에서 만들므로, 그 GPT 호출의 ai_call_log 에는
 * conversation_id 가 없다 (user_id 는 있음). GPT 가 실패하면 대화를 만들지 않기로 한 사용자 결정 때문이다
 * (먼저 만들면 실패할 때마다 빈 대화가 남는다). 이어지는 질문부터는 conversation_id 가 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPipeline {

    private final ChatRateLimiter rateLimiter;
    private final ChatContextReader contextReader;
    private final GptClient gptClient;
    private final ChatWriter writer;

    public ChatAnswerInfo ask(UUID userId, AskCommand command) {
        rateLimiter.check(userId);
        ChatContext ctx = contextReader.read(userId, command);

        ChatGptResponse response = gptClient.call(GptPurpose.CHAT, ChatPrompt.request(ctx), ChatGptResponse.class,
                GptContext.conversation(userId, ctx.conversationId()));
        ChatAnswerValidator.Result answer = ChatAnswerValidator.validate(response, ctx);
        if (!answer.log().isEmpty()) {
            // 내용(계약서 글)은 남기지 않고 종류만
            log.info("챗봇 답변 검증: conversationId={}, changes={}", ctx.conversationId(), answer.log());
        }

        ChatWriter.Saved saved = writer.save(ctx, answer);
        return new ChatAnswerInfo(saved.conversationId(), saved.messageId(), saved.newConversation(),
                answer.answerType(), answer.answer(), ctx.linkedDocument(), answer.references(), answer.partial(),
                answer.actions());
    }
}
