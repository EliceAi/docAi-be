package com.team.docai.web.analysis.dto.response;

import com.team.docai.domain.analysis.dto.ManualInputInfo;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;

/** 미인식 항목 입력 응답. state 는 그대로(확인된 값처럼 보이지 않게), source 는 MANUAL ('직접 입력' 표시) */
public record ManualInputResponse(String label, String value, KeyTermState state, KeyTermSource source) {

    public static ManualInputResponse from(ManualInputInfo info) {
        return new ManualInputResponse(info.label(), info.value(), info.state(), info.source());
    }
}
