package com.team.docai.domain.analysis.dto;

import java.util.List;

/**
 * GPT 2차(JUDGE, JUDGE_MORE) 응답. 출력 스키마는 JudgePrompt.schema() 와 필드가 1:1 로 같다
 * (GptClient 가 모르는 필드·빠진 필드를 스키마 불일치로 본다). 값이 없을 수 있는 칸은 null.
 * 화면에 나가기 전에 서버 검증(JudgeValidator, 아키텍처 8단계)을 거친다.
 *
 * @param judgments              보낸 의심 조항마다 판단 하나
 * @param needMore               근거가 부족해 법령을 더 찾아 달라는 조항 (7-1, 분석당 1회만 들어 줌)
 * @param questionsBeforeSigning 계약서 전체에서 서명 전에 물어볼 것
 */
public record JudgeResponse(
        List<Judgment> judgments,
        List<NeedMore> needMore,
        List<String> questionsBeforeSigning
) {

    /**
     * @param verdict         NEEDS_CHECK / UNCERTAIN / OK
     * @param headline        결과 카드 제목 (analysis_clause.headline, 150자)
     * @param easyExplanation 조항 뜻을 쉬운 말로 (analysis_clause.easy_explanation)
     * @param whyCheck        확인이 필요한 이유 (analysis_clause.why_check). OK 면 null
     * @param citedIds        이 조항에 준 근거 ID (L1, G3) 중 인용한 것
     * @param relatedClauses  함께 봐야 하는 조항 키 (목차의 키)
     * @param askCounterpart  상대방에게 확인할 것
     */
    public record Judgment(
            String clauseKey,
            String verdict,
            String headline,
            String easyExplanation,
            String whyCheck,
            List<String> citedIds,
            List<String> relatedClauses,
            List<String> askCounterpart
    ) {
    }

    /**
     * @param want           더 필요한 자료 설명 (예: "수습 기간 최저임금 감액 기준")
     * @param searchKeywords 법령 검색어
     */
    public record NeedMore(String clauseKey, String want, List<String> searchKeywords) {
    }
}
