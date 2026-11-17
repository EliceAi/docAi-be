package com.team.docai.domain.analysis.dto;

import com.team.docai.domain.analysis.entity.ScheduleCandidate;

import java.time.LocalDate;

/**
 * A4 AnalysisService.getScheduleCandidates 의 항목. source: WRITTEN / REVIEW. date 가 null 이면 누락 후보.
 */
public record ScheduleCandidateInfo(
        Long candidateId,
        String dateKind,
        String title,
        LocalDate date,
        String source,
        String location,
        Integer pageNo,
        String conflictGroup,
        boolean defaultSelected
) {

    public static ScheduleCandidateInfo from(ScheduleCandidate c) {
        return new ScheduleCandidateInfo(c.getCandidateId(), c.getDateKind(), c.getTitle(), c.getDate(),
                c.getSource().name(), c.getLocation(), c.getPageNo(), c.getConflictGroup(), c.isDefaultSelected());
    }
}
