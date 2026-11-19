package com.team.docai.domain.chat.dto;

import java.util.List;

/**
 * GPT 챗봇(CHAT) 출력 (구조화 출력, ChatPrompt.schema 와 같은 모양). 서버 안에서만 쓰고 그대로 내보내지 않는다.
 * ChatAnswerValidator 가 검증한 뒤 저장·응답한다.
 *
 * @param answerType          ANSWERED / PARTIAL / OUT_OF_SCOPE
 * @param citedIds            인용한 법령 자료 ID (서버가 준 L1, L2 ...)
 * @param usedSources         참고한 것: CURRENT_SCREEN / MY_DOCUMENT / APP_GUIDE (법령은 citedIds 로)
 * @param mentionsLaw         답변에 법이 어떻게 정하는지(법적 판단)를 썼는지
 * @param partial             PARTIAL 일 때 확인한 것 / 알 수 없는 것 (아니면 빈 목록)
 * @param actions             이동 버튼 후보 (type, target: 화면 번호 또는 조항 키)
 * @param conversationSummary 이번 질문·답변까지 포함한 대화 요약 (다음 질문 맥락용, 사용자에게 보이지 않음)
 */
public record ChatGptResponse(
        String answerType,
        String answer,
        List<String> citedIds,
        List<String> usedSources,
        boolean mentionsLaw,
        Partial partial,
        List<Action> actions,
        String conversationSummary
) {
    public record Partial(List<String> confirmed, List<String> unknown) {
    }

    public record Action(String type, String target) {
    }
}
