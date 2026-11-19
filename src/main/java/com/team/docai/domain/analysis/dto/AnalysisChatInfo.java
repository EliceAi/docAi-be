package com.team.docai.domain.analysis.dto;

import com.team.docai.domain.analysis.entity.AnalysisStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 챗봇(AI 질문)이 맥락으로 쓰는 분석 결과 요약. 내 분석(삭제되지 않은 내 계약서의 분석)만 만든다.
 * <ul>
 *   <li>COMPLETED 가 아니면 목록은 모두 비어 있다 ("분석 전 계약서는 내용을 근거로 답하지 않는다", AI 질문 명세 비고).</li>
 *   <li>조항은 보이는 조항만 (제외·판정 중 빼고). UNCERTAIN·OK 조항은 whyCheck 를 넣지 않는다 (결과 조회와 같은 규칙).</li>
 *   <li>근거 법령은 NEEDS_CHECK 조항이 인용하고 검증을 통과한 조문만, 내용은 분석 당시 스냅샷(ref_text).</li>
 * </ul>
 *
 * @param contractId 분석한 버전의 계약서
 */
public record AnalysisChatInfo(
        Long analysisId,
        Long contractId,
        AnalysisStatus status,
        String documentTypeCode,
        OffsetDateTime completedAt,
        List<KeyTerm> keyTerms,
        List<Clause> clauses,
        List<Finding> findings,
        List<LawRef> lawRefs
) {
    public boolean completed() {
        return status == AnalysisStatus.COMPLETED;
    }

    public record KeyTerm(String label, String value, String state) {
    }

    /** originalText 는 UNREADABLE 이면 null */
    public record Clause(Long clauseId, String clauseKey, String articleNo, String title, String verdict,
                         String headline, String oneLine, int pageNo, String easyExplanation, String whyCheck,
                         String originalText) {
    }

    /** kind: MISSING_ITEM / DEADLINE */
    public record Finding(String kind, String name, String description, LocalDate dueDate) {
    }

    /**
     * @param lawArticleId 조문 ID (같은 조문을 한 번만 보내기 위해)
     * @param label        "근로기준법 제20조" (항 없이)
     * @param title        조문 제목 (없으면 null)
     * @param text         분석 당시 AI 에게 보낸 근거 내용 (ref_text)
     */
    public record LawRef(Long clauseId, Long lawArticleId, String label, String title, String text) {
    }
}
