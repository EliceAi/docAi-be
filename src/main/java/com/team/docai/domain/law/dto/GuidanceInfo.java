package com.team.docai.domain.law.dto;

import com.team.docai.domain.law.entity.Guidance;

/** 해석 메모. */
public record GuidanceInfo(Long guidanceId, String issueTagCode, String title, String content, String sourceNote) {

    public static GuidanceInfo from(Guidance g) {
        return new GuidanceInfo(g.getGuidanceId(), g.getIssueTagCode(), g.getTitle(), g.getContent(), g.getSourceNote());
    }
}
