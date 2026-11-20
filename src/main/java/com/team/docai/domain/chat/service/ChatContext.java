package com.team.docai.domain.chat.service;

import com.team.docai.domain.analysis.dto.AnalysisChatInfo;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.chat.dto.LinkedDocumentInfo;
import com.team.docai.domain.chat.entity.ChatRole;
import com.team.docai.domain.comparison.dto.ComparisonForChatInfo.Change;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 질문 한 번의 맥락 (ChatContextReader 가 읽기 트랜잭션 안에서 모으고, 트랜잭션 밖에서 GPT 지시문·검증·저장에 쓴다).
 * 엔티티를 담지 않는다 (트랜잭션이 끝난 뒤에도 쓰므로).
 *
 * @param conversationId    이어 가는 대화. 새 대화면 null
 * @param previousSummary   읽을 때 본 대화 요약 (요약 조건부 갱신의 기대값)
 * @param clauseId          보고 있던 조항 (검증됨). 질문 메시지 refs_json 에 저장
 * @param contractId        연결할(새 대화) 또는 연결된(기존 대화) 계약서
 * @param analysisIdToStore 새 대화에 저장할 analysis_id (요청한 분석, 검증됨). 기존 대화면 null (바꾸지 않음)
 * @param comparisonId      연결할(새 대화) 또는 연결된(기존 대화) 비교
 * @param analysis          답변에 내용을 쓸 완료 분석. 없으면 null (분석 전 계약서는 내용을 근거로 답하지 않는다)
 * @param pendingStatus     요청·연결된 분석이 아직 완료가 아니면 그 상태 (ANALYZING / FAILED), 아니면 null
 * @param focusClause       보고 있던 조항 (analysis 의 보이는 조항)
 * @param comparison        비교 결과 (연결됐으면)
 * @param sources           GPT 가 인용할 수 있는 법령 자료 (ID L1, L2 ...)
 * @param recent            최근 대화 (오래된 순)
 */
record ChatContext(
        UUID userId,
        Long conversationId,
        String previousSummary,
        String question,
        String screen,
        Long clauseId,
        Long contractId,
        Long analysisIdToStore,
        Long comparisonId,
        LinkedDocumentInfo linkedDocument,
        AnalysisChatInfo analysis,
        AnalysisStatus pendingStatus,
        AnalysisChatInfo.Clause focusClause,
        Comparison comparison,
        List<Source> sources,
        List<PastMessage> recent
) {
    boolean newConversation() {
        return conversationId == null;
    }

    /** 조항 키 → 화면에 쓸 이름 (제7조, 「임금」 조항, 관련 조항) */
    Map<String, String> clauseNames() {
        Map<String, String> names = new LinkedHashMap<>();
        if (analysis != null) {
            analysis.clauses().forEach(c -> names.put(c.clauseKey(), displayName(c.articleNo(), c.title())));
        }
        return names;
    }

    /** 조항 키 → 조항 ID (이동 버튼용, 보이는 조항만) */
    Map<String, Long> clauseIds() {
        Map<String, Long> ids = new LinkedHashMap<>();
        if (analysis != null) {
            analysis.clauses().forEach(c -> ids.put(c.clauseKey(), c.clauseId()));
        }
        return ids;
    }

    /** 2차 검증(JudgeValidator.displayName)과 같은 규칙: 조 번호, 없으면 "「제목」 조항", 둘 다 없으면 "관련 조항" */
    static String displayName(String articleNo, String title) {
        if (articleNo != null && !articleNo.isBlank()) {
            return articleNo.trim();
        }
        if (title != null && !title.isBlank()) {
            return "「" + title.trim() + "」 조항";
        }
        return "관련 조항";
    }

    /**
     * 법령 자료 하나.
     *
     * @param id             GPT 에 보이는 ID (L1 ...)
     * @param referenceLabel 참고한 정보에 보일 이름 ("근로기준법 제20조", 항 없이)
     * @param heading        GPT 에 보이는 제목 (항까지 + 조문 제목)
     * @param text           조문 내용 (분석에서 온 것은 분석 당시 스냅샷)
     */
    record Source(String id, Long lawArticleId, String referenceLabel, String heading, String text) {
    }

    record PastMessage(ChatRole role, String content) {
    }

    /**
     * 비교 결과. 모두 B {@code ComparisonService.getForChat} 에서 읽는다 (바뀐 곳은 change_id 순 최대 20).
     *
     * @param status PENDING_CONFIRM / CONFIRMED / DISCARDED / SUPERSEDED
     */
    record Comparison(Long comparisonId, Long previousContractId, String status, String unchangedSummary,
                      List<Change> changes) {
    }
}
