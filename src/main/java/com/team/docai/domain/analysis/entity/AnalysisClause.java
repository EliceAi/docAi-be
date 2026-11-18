package com.team.docai.domain.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * 분석 조항 (analysis_clause). 4단계(묶음 병합)에 verdict = JUDGING 으로 저장하고, 7단계 판정·9단계 OK 채우기로 바뀐다.
 * verdict·excluded 는 DB 기본값이 있지만 JPA 는 NULL 을 보내므로 create() 에서 직접 채운다.
 * 길이 (ExtractMerger): GPT 가 지은 칸(one_line, location, headline)은 *_MAX_LENGTH 로 잘라 넣고, 계약서에서 온 칸
 * (title, article_no)은 자르지 않고 넘으면 null 로 넣는다. original_text 는 TEXT. 기록은 analysis.validation_log_json.
 */
@Entity
@Table(name = "analysis_clause")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisClause {

    public static final int CLAUSE_KEY_MAX_LENGTH = 20;
    public static final int ARTICLE_NO_MAX_LENGTH = 20;
    public static final int TITLE_MAX_LENGTH = 100;
    public static final int ONE_LINE_MAX_LENGTH = 200;
    public static final int LOCATION_MAX_LENGTH = 100;
    public static final int ISSUE_TAG_CODE_MAX_LENGTH = 40;
    public static final int HEADLINE_MAX_LENGTH = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long clauseId;

    @Column(nullable = false)
    private Long analysisId;

    /** 서버가 병합 때 쪽·위치 순서로 새로 매긴 키 (c1, c2 ...). GPT 값을 쓰지 않는다. */
    @Column(nullable = false, length = CLAUSE_KEY_MAX_LENGTH)
    private String clauseKey;

    @Column(length = ARTICLE_NO_MAX_LENGTH)
    private String articleNo;

    @Column(length = TITLE_MAX_LENGTH)
    private String title;

    /** 원문. verdict = UNREADABLE 일 때만 null (ck_clause_text) */
    @Column(columnDefinition = "text")
    private String originalText;

    @Column(length = ONE_LINE_MAX_LENGTH)
    private String oneLine;

    @Column(nullable = false)
    private int pageNo;

    @Column(length = LOCATION_MAX_LENGTH)
    private String location;

    @Column(nullable = false)
    private boolean issueFound;

    @Column(columnDefinition = "text")
    private String issueSummary;

    /** 꼬리표 (issue_tag.code). 목록에 없는 값은 저장 전에 null 로 바꾼다. */
    @Column(length = ISSUE_TAG_CODE_MAX_LENGTH)
    private String issueTagCode;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<LawHint> lawHints;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> searchKeywords;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClauseVerdict verdict;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private BasisType basisType;

    @Column(length = HEADLINE_MAX_LENGTH)
    private String headline;

    @Column(columnDefinition = "text")
    private String easyExplanation;

    @Column(columnDefinition = "text")
    private String whyCheck;

    @JdbcTypeCode(SqlTypes.JSON)
    private NumberComparison comparisonJson;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> askCounterpartJson;

    private Integer priority;

    @Column(nullable = false)
    private boolean excluded;

    @Builder
    private AnalysisClause(Long analysisId, String clauseKey, String articleNo, String title, String originalText,
                           String oneLine, int pageNo, String location, boolean issueFound, String issueSummary,
                           String issueTagCode, List<LawHint> lawHints, List<String> searchKeywords,
                           ClauseVerdict verdict, boolean excluded) {
        this.analysisId = analysisId;
        this.clauseKey = clauseKey;
        this.articleNo = articleNo;
        this.title = title;
        this.originalText = originalText;
        this.oneLine = oneLine;
        this.pageNo = pageNo;
        this.location = location;
        this.issueFound = issueFound;
        this.issueSummary = issueSummary;
        this.issueTagCode = issueTagCode;
        this.lawHints = lawHints;
        this.searchKeywords = searchKeywords;
        this.verdict = verdict;
        this.excluded = excluded;
    }

    /**
     * 4단계 병합 결과 저장. verdict 는 JUDGING, excluded 는 false.
     * 원문을 읽지 못한 조항(originalText == null)은 ck_clause_text 때문에 판정 중으로 둘 수 없어 UNREADABLE 로 저장한다.
     */
    public static AnalysisClause create(Long analysisId, String clauseKey, String articleNo, String title,
                                        String originalText, String oneLine, int pageNo, String location,
                                        boolean issueFound, String issueSummary, String issueTagCode,
                                        List<LawHint> lawHints, List<String> searchKeywords) {
        return AnalysisClause.builder()
                .analysisId(analysisId)
                .clauseKey(clauseKey)
                .articleNo(articleNo)
                .title(title)
                .originalText(originalText)
                .oneLine(oneLine)
                .pageNo(pageNo)
                .location(location)
                .issueFound(issueFound)
                .issueSummary(issueSummary)
                .issueTagCode(issueTagCode)
                .lawHints(lawHints)
                .searchKeywords(searchKeywords)
                .verdict(originalText == null ? ClauseVerdict.UNREADABLE : ClauseVerdict.JUDGING)
                .excluded(false)
                .build();
    }
}
