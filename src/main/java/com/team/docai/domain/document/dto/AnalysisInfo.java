package com.team.docai.domain.document.dto;

import java.util.List;
public record AnalysisInfo(String mode,String summary,boolean partial,List<Condition> conditions,List<Clause> clauses,List<String> missingItems,List<Suggestion> schedules) {
    public record Condition(String label,String value,String source) {}
    public record Clause(String id,String title,String original,String severity,String explanation,String legalReference,String source) {}
    public record Suggestion(String key,String title,String date,String source,String note) {}
}
