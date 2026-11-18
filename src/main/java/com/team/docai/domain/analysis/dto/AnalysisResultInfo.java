package com.team.docai.domain.analysis.dto;

import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.BasisType;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.entity.NumberComparison;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 분석 결과 조회 (GET /api/analyses/{analysisId}) 의 결과.
 * failure 는 FAILED 일 때만, result 는 COMPLETED 일 때만 채우고 그 밖에는 null.
 *
 * @param version   분석한 계약서 버전 번호 (contract_version.version_no)
 * @param readDone  읽기 단계가 끝난 쪽 묶음 수 (analysis_chunk DONE)
 * @param readTotal 쪽 묶음 수
 */
public record AnalysisResultInfo(
        Long analysisId,
        Long contractId,
        int version,
        AnalysisStatus status,
        AnalysisStep progressStep,
        int readDone,
        int readTotal,
        Failure failure,
        Result result
) {

    /** FAILED 일 때만. pageNo 는 PAGE_UNREADABLE, detectedTypeName 은 TYPE_MISMATCH 일 때 값이 있다 */
    public record Failure(AnalysisFailReason reason, Integer pageNo, String detectedTypeName) {
    }

    /**
     * COMPLETED 분석의 결과. 판정 중(JUDGING)·제외(excluded) 조항은 넣지 않는다. 예시 분석(샘플 JSON)도 이 모양이다.
     *
     * @param foundDateCount 찾은 날짜 수 (일정 후보 중 날짜가 있는 것)
     */
    public record Result(
            String title,
            String documentTypeCode,
            String checkLevel,
            OffsetDateTime analyzedAt,
            Coverage coverage,
            List<KeyTerm> keyTerms,
            Counts counts,
            List<PriorityClause> priorityClauses,
            List<Finding> missingItems,
            List<Finding> deadlines,
            List<ClauseSummary> clauses,
            List<String> questionsBeforeSigning,
            List<String> limitations,
            long foundDateCount,
            SimilarContract similarContract,
            String disclaimer
    ) {
    }

    /** maskedCount 는 이 버전 쪽들의 contract_page.masked_count 합 */
    public record Coverage(int pageCount, List<Integer> readPages, List<Integer> partialPages, int maskedCount) {
    }

    public record KeyTerm(String label, String value, KeyTermState state, KeyTermSource source) {
    }

    /**
     * @param needsCheck   조항 NEEDS_CHECK + UNCERTAIN 수
     * @param missing      빠진 항목 수 (missingItems)
     * @param deadline     기한 수 (deadlines)
     * @param totalClauses 결과에 나오는 전체 조항 수
     */
    public record Counts(long needsCheck, long missing, long deadline, long totalClauses) {
    }

    /** title = headline, description = why_check. UNCERTAIN 이면 description 은 null, laws 는 빈 배열 */
    public record PriorityClause(
            Long clauseId,
            ClauseVerdict verdict,
            String title,
            String description,
            NumberComparison comparison,
            List<LawRef> laws,
            BasisType basisType,
            List<RelatedClause> relatedClauses
    ) {
    }

    /** 근거 법령. article 은 "제6조", 가지번호가 있으면 "제10조의2" (항은 넣지 않는다) */
    public record LawRef(String lawName, String article) {
    }

    public record RelatedClause(Long clauseId, String articleNo, String title) {
    }

    /** 빠진 항목(MISSING_ITEM, dueDate 항상 null)과 기한(DEADLINE)이 같은 모양 */
    public record Finding(String name, String description, LocalDate dueDate, List<LawRef> laws) {
    }

    public record ClauseSummary(Long clauseId, String articleNo, String title, ClauseVerdict verdict, int pageNo) {
    }

    /** scheduleCount 는 그 계약서에 저장된 일정 수 */
    public record SimilarContract(Long contractId, String title, OffsetDateTime analyzedAt, long scheduleCount) {
    }
}
