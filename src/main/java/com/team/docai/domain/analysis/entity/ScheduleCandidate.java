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

import java.time.LocalDate;

/**
 * 일정 후보 (schedule_candidate). 분석이 만드는 결과라 A 가 주인이고, B 의 일정 API 는 AnalysisService 로 읽는다.
 */
@Entity
@Table(name = "schedule_candidate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleCandidate {

    public static final int DATE_KIND_MAX_LENGTH = 30;
    public static final int TITLE_MAX_LENGTH = 50;
    public static final int LOCATION_MAX_LENGTH = 100;
    public static final int CONFLICT_GROUP_MAX_LENGTH = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long candidateId;

    @Column(nullable = false)
    private Long analysisId;

    /** CONTRACT_START / CONTRACT_END / MOVE_IN / PAYDAY / RENEWAL_NOTICE / REPORT_DEADLINE / WAGE_CHANGE ... (목록이 열려 있어 문자열) */
    @Column(nullable = false, length = DATE_KIND_MAX_LENGTH)
    private String dateKind;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    /** 계약서에 없으면 null (누락 후보) */
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CandidateSource source;

    @Column(length = LOCATION_MAX_LENGTH)
    private String location;

    private Integer pageNo;

    @Column(length = CONFLICT_GROUP_MAX_LENGTH)
    private String conflictGroup;

    @Column(nullable = false)
    private boolean defaultSelected;

    @Builder
    private ScheduleCandidate(Long analysisId, String dateKind, String title, LocalDate date, CandidateSource source,
                              String location, Integer pageNo, String conflictGroup, boolean defaultSelected) {
        this.analysisId = analysisId;
        this.dateKind = dateKind;
        this.title = title;
        this.date = date;
        this.source = source;
        this.location = location;
        this.pageNo = pageNo;
        this.conflictGroup = conflictGroup;
        this.defaultSelected = defaultSelected;
    }

    public static ScheduleCandidate create(Long analysisId, String dateKind, String title, LocalDate date,
                                           CandidateSource source, String location, Integer pageNo,
                                           String conflictGroup, boolean defaultSelected) {
        return ScheduleCandidate.builder()
                .analysisId(analysisId)
                .dateKind(dateKind)
                .title(title)
                .date(date)
                .source(source)
                .location(location)
                .pageNo(pageNo)
                .conflictGroup(conflictGroup)
                .defaultSelected(defaultSelected)
                .build();
    }
}
