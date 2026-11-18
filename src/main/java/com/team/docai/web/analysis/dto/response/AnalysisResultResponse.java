package com.team.docai.web.analysis.dto.response;

import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.BasisType;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 분석 결과 조회 응답 (GET /api/analyses/{analysisId}). failure 는 FAILED, result 는 COMPLETED 일 때만 값이 있고 그 밖에는 null.
 */
public record AnalysisResultResponse(
        Long analysisId,
        Long contractId,
        int version,
        AnalysisStatus status,
        AnalysisStep progressStep,
        ReadProgress readProgress,
        Failure failure,
        Result result
) {
    public record ReadProgress(int done, int total) {
    }

    public record Failure(AnalysisFailReason reason, Integer pageNo, String detectedTypeName) {
    }

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
            List<Clause> clauses,
            List<String> questionsBeforeSigning,
            List<String> limitations,
            long foundDateCount,
            SimilarContract similarContract,
            String disclaimer
    ) {
        public static Result from(AnalysisResultInfo.Result r) {
            if (r == null) {
                return null;
            }
            AnalysisResultInfo.Coverage c = r.coverage();
            AnalysisResultInfo.Counts n = r.counts();
            AnalysisResultInfo.SimilarContract s = r.similarContract();
            return new Result(
                    r.title(),
                    r.documentTypeCode(),
                    r.checkLevel(),
                    r.analyzedAt(),
                    c == null ? null : new Coverage(c.pageCount(), c.readPages(), c.partialPages(), c.maskedCount()),
                    r.keyTerms().stream().map(k -> new KeyTerm(k.label(), k.value(), k.state(), k.source())).toList(),
                    n == null ? null : new Counts(n.needsCheck(), n.missing(), n.deadline(), n.totalClauses()),
                    r.priorityClauses().stream().map(PriorityClause::from).toList(),
                    r.missingItems().stream().map(Finding::from).toList(),
                    r.deadlines().stream().map(Finding::from).toList(),
                    r.clauses().stream()
                            .map(x -> new Clause(x.clauseId(), x.articleNo(), x.title(), x.verdict(), x.pageNo()))
                            .toList(),
                    r.questionsBeforeSigning(),
                    r.limitations(),
                    r.foundDateCount(),
                    s == null ? null : new SimilarContract(s.contractId(), s.title(), s.analyzedAt(), s.scheduleCount()),
                    r.disclaimer());
        }
    }

    public record Coverage(int pageCount, List<Integer> readPages, List<Integer> partialPages, int maskedCount) {
    }

    public record KeyTerm(String label, String value, KeyTermState state, KeyTermSource source) {
    }

    public record Counts(long needsCheck, long missing, long deadline, long totalClauses) {
    }

    public record PriorityClause(
            Long clauseId,
            ClauseVerdict verdict,
            String title,
            String description,
            Comparison comparison,
            List<Law> laws,
            BasisType basisType,
            List<RelatedClause> relatedClauses
    ) {
        static PriorityClause from(AnalysisResultInfo.PriorityClause p) {
            var c = p.comparison();
            return new PriorityClause(
                    p.clauseId(),
                    p.verdict(),
                    p.title(),
                    p.description(),
                    c == null ? null
                            : new Comparison(c.leftLabel(), c.leftValue(), c.operator(), c.rightLabel(), c.rightValue()),
                    Law.from(p.laws()),
                    p.basisType(),
                    p.relatedClauses().stream()
                            .map(r -> new RelatedClause(r.clauseId(), r.articleNo(), r.title()))
                            .toList());
        }
    }

    public record Comparison(String leftLabel, String leftValue, String operator, String rightLabel, String rightValue) {
    }

    public record Law(String lawName, String article) {
        static List<Law> from(List<AnalysisResultInfo.LawRef> laws) {
            return laws.stream().map(l -> new Law(l.lawName(), l.article())).toList();
        }
    }

    public record RelatedClause(Long clauseId, String articleNo, String title) {
    }

    public record Finding(String name, String description, LocalDate dueDate, List<Law> laws) {
        static Finding from(AnalysisResultInfo.Finding f) {
            return new Finding(f.name(), f.description(), f.dueDate(), Law.from(f.laws()));
        }
    }

    public record Clause(Long clauseId, String articleNo, String title, ClauseVerdict verdict, int pageNo) {
    }

    public record SimilarContract(Long contractId, String title, OffsetDateTime analyzedAt, long scheduleCount) {
    }

    public static AnalysisResultResponse from(AnalysisResultInfo info) {
        AnalysisResultInfo.Failure f = info.failure();
        return new AnalysisResultResponse(
                info.analysisId(),
                info.contractId(),
                info.version(),
                info.status(),
                info.progressStep(),
                new ReadProgress(info.readDone(), info.readTotal()),
                f == null ? null : new Failure(f.reason(), f.pageNo(), f.detectedTypeName()),
                Result.from(info.result()));
    }
}
