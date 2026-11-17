package com.team.docai.domain.law.entity;

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

import java.time.LocalDate;

/** 해석 메모 (guidance). 사람이 요약해 쓰고, 출처가 API 해석례면 그 번호를 같이 둔다 (설계_점검 37번). */
@Entity
@Table(name = "guidance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Guidance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long guidanceId;

    @Column(nullable = false, length = 40)
    private String issueTagCode;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 200)
    private String sourceNote;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private GuidanceSourceType sourceType;

    @Column(length = 40)
    private String sourceRef;

    @Column(nullable = false)
    private LocalDate reviewedAt;

    @Builder
    private Guidance(String issueTagCode, String title, String content, String sourceNote,
                     GuidanceSourceType sourceType, String sourceRef, LocalDate reviewedAt) {
        this.issueTagCode = issueTagCode;
        this.title = title;
        this.content = content;
        this.sourceNote = sourceNote;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.reviewedAt = reviewedAt;
    }

    public static Guidance create(String issueTagCode, String title, String content, String sourceNote,
                                  GuidanceSourceType sourceType, String sourceRef, LocalDate reviewedAt) {
        return Guidance.builder().issueTagCode(issueTagCode).title(title).content(content).sourceNote(sourceNote)
                .sourceType(sourceType).sourceRef(sourceRef).reviewedAt(reviewedAt).build();
    }
}
