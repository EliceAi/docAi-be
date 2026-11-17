package com.team.docai.domain.law.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 조문 (law_article). 저장 단위는 항이다. 항의 호·목은 content 에 줄바꿈으로 이어 붙이고,
 * 항이 없는 조문은 paragraph_no = null 인 행 하나에 조문 내용 전체를 넣는다 (ERD, 설계_점검 31번).
 * search_vector 는 DB 가 계산하는 칸이라 매핑하지 않는다. 검색은 native query.
 */
@Entity
@Table(name = "law_article")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LawArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long lawArticleId;

    @Column(nullable = false)
    private Long lawId;

    @Column(nullable = false)
    private Integer articleNo;

    @Column(nullable = false)
    private Integer articleBranchNo;

    private Integer paragraphNo;

    @Column(length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 20)
    private String sourceMst;

    private LocalDate promulgatedAt;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    @Builder
    private LawArticle(Long lawId, Integer articleNo, Integer articleBranchNo, Integer paragraphNo, String title,
                       String content, String sourceMst, LocalDate promulgatedAt, LocalDate effectiveFrom) {
        this.lawId = lawId;
        this.articleNo = articleNo;
        this.articleBranchNo = articleBranchNo;
        this.paragraphNo = paragraphNo;
        this.title = title;
        this.content = content;
        this.sourceMst = sourceMst;
        this.promulgatedAt = promulgatedAt;
        this.effectiveFrom = effectiveFrom;
    }

    /** articleBranchNo 가 null 이면 0 (DB 기본값 칸이지만 JPA 는 NULL 을 보내므로 직접 넣는다). */
    public static LawArticle create(Long lawId, int articleNo, Integer articleBranchNo, Integer paragraphNo,
                                    String title, String content, String sourceMst,
                                    LocalDate promulgatedAt, LocalDate effectiveFrom) {
        return LawArticle.builder()
                .lawId(lawId)
                .articleNo(articleNo)
                .articleBranchNo(articleBranchNo == null ? 0 : articleBranchNo)
                .paragraphNo(paragraphNo)
                .title(title)
                .content(content)
                .sourceMst(sourceMst)
                .promulgatedAt(promulgatedAt)
                .effectiveFrom(effectiveFrom)
                .build();
    }

    /** 같은 조문·항·시행일 행을 다시 받았을 때 내용 갱신 (강제 갱신으로 같은 개정본을 다시 저장할 때). */
    public void revise(String title, String content, String sourceMst, LocalDate promulgatedAt) {
        this.title = title;
        this.content = content;
        this.sourceMst = sourceMst;
        this.promulgatedAt = promulgatedAt;
    }
}
