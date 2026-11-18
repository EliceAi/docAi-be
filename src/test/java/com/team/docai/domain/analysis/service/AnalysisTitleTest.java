package com.team.docai.domain.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 분석이 정하는 계약서 이름: 1차 문서 제목, 없거나 비거나 읽을 수 없으면 문서 종류 이름 */
class AnalysisTitleTest {

    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 이름은_1차_문서_제목이고_없거나_비면_문서_종류_이름() throws Exception {
        assertThat(AnalysisTitle.resolve(mapper.readTree("{\"documentTitle\":\" 카페 알바 근로계약서 \"}"), "EMPLOYMENT"))
                .isEqualTo("카페 알바 근로계약서");
        assertThat(AnalysisTitle.resolve(mapper.readTree("{\"documentTitle\":null}"), "EMPLOYMENT")).isEqualTo("근로계약서");
        assertThat(AnalysisTitle.resolve(mapper.readTree("{\"documentTitle\":\"  \"}"), "LEASE")).isEqualTo("주택 임대차계약서");
        assertThat(AnalysisTitle.resolve(mapper.missingNode(), "EMPLOYMENT")).isEqualTo("근로계약서");
    }

    @Test
    void JSON_글에서도_같은_규칙이고_비거나_깨지면_문서_종류_이름() {
        assertThat(AnalysisTitle.resolve(mapper, "{\"documentTitle\":\"표준근로계약서\"}", "EMPLOYMENT")).isEqualTo("표준근로계약서");
        assertThat(AnalysisTitle.resolve(mapper, null, "EMPLOYMENT")).isEqualTo("근로계약서");
        assertThat(AnalysisTitle.resolve(mapper, "{깨짐", "LEASE")).isEqualTo("주택 임대차계약서");
    }
}
