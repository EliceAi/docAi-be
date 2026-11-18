package com.team.docai.domain.analysis.service;

import com.team.docai.domain.analysis.dto.ExtractResponse;
import com.team.docai.domain.analysis.entity.LawHint;

import java.util.ArrayList;
import java.util.List;

/** 1차 응답을 짧게 만드는 테스트 도우미 */
final class ExtractFixtures {

    private ExtractFixtures() {
    }

    static ExtractResponse.Clause clause(String key, int page, String articleNo, String title, String text) {
        return new ExtractResponse.Clause(key, page, articleNo, title, text, title + " 요약", page + "쪽", false, false,
                false, null, null, List.of(), List.of(), List.of());
    }

    static ExtractResponse.Clause continuing(ExtractResponse.Clause c, boolean fromPrevious, boolean toNext) {
        return new ExtractResponse.Clause(c.clauseKey(), c.pageNo(), c.articleNo(), c.title(), c.originalText(),
                c.oneLine(), c.location(), fromPrevious, toNext, c.issueFound(), c.issueSummary(), c.issueTag(),
                c.lawHints(), c.searchKeywords(), c.relatedClauseKeys());
    }

    static ExtractResponse.Clause issue(ExtractResponse.Clause c, String tag, List<String> related) {
        return new ExtractResponse.Clause(c.clauseKey(), c.pageNo(), c.articleNo(), c.title(), c.originalText(),
                c.oneLine(), c.location(), c.continuesFromPreviousPage(), c.continuesToNextPage(), true,
                "의심 요약", tag, List.of(new LawHint("근로기준법", "제20조")), List.of("위약금", "교육비"), related);
    }

    static ExtractResponse.Clause withTexts(ExtractResponse.Clause c, String oneLine, String location) {
        return new ExtractResponse.Clause(c.clauseKey(), c.pageNo(), c.articleNo(), c.title(), c.originalText(),
                oneLine, location, c.continuesFromPreviousPage(), c.continuesToNextPage(), c.issueFound(),
                c.issueSummary(), c.issueTag(), c.lawHints(), c.searchKeywords(), c.relatedClauseKeys());
    }

    static ExtractResponse.KeyTerm written(String label, String value, String clauseKey, String evidence,
                                           ExtractResponse.ValueJson valueJson) {
        return new ExtractResponse.KeyTerm(label, "WRITTEN", value, valueJson, clauseKey, evidence);
    }

    static ExtractResponse.KeyTerm state(String label, String state) {
        return new ExtractResponse.KeyTerm(label, state, null, null, null, null);
    }

    static ExtractResponse.ValueJson wage(String type, long amount) {
        return new ExtractResponse.ValueJson(type, amount, null, null, null, null);
    }

    static ExtractResponse response(int pageFrom, int pageTo, List<ExtractResponse.Clause> clauses,
                                    List<ExtractResponse.KeyTerm> keyTerms) {
        List<ExtractResponse.PageRead> pages = new ArrayList<>();
        for (int p = pageFrom; p <= pageTo; p++) {
            pages.add(new ExtractResponse.PageRead(p, "READ"));
        }
        return new ExtractResponse("표준근로계약서", null, new ExtractResponse.TypeCheck(true, null), pages, clauses,
                keyTerms, List.of());
    }

    static ExtractResponse withPages(ExtractResponse r, List<ExtractResponse.PageRead> pages) {
        return new ExtractResponse(r.documentTitle(), r.contractDate(), r.typeCheck(), pages, r.clauses(),
                r.keyTerms(), r.dates());
    }

    static ExtractResponse withType(ExtractResponse r, ExtractResponse.TypeCheck typeCheck) {
        return new ExtractResponse(r.documentTitle(), r.contractDate(), typeCheck, r.pages(), r.clauses(),
                r.keyTerms(), r.dates());
    }

    static ExtractResponse withDates(ExtractResponse r, String contractDate, List<ExtractResponse.DateItem> dates) {
        return new ExtractResponse(r.documentTitle(), contractDate, r.typeCheck(), r.pages(), r.clauses(),
                r.keyTerms(), dates);
    }
}
