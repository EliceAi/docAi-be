package com.team.docai.domain.analysis.dto;

import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;

/**
 * 미인식 항목 입력 결과. state 는 그대로(UNREADABLE), source 는 MANUAL.
 */
public record ManualInputInfo(String label, String value, KeyTermState state, KeyTermSource source) {

    public static ManualInputInfo from(AnalysisKeyTerm term) {
        return new ManualInputInfo(term.getLabel(), term.getValue(), term.getState(), term.getSource());
    }
}
