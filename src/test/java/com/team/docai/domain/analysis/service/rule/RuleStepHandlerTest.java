package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleStepHandlerTest {

    static AnalysisKeyTerm term(String label, Map<String, Object> json, KeyTermState state) {
        return AnalysisKeyTerm.create(1L, null, label, null, json, state, KeyTermSource.WRITTEN, null, 1);
    }

    @Test
    void 근로시간_행이_없으면_null_있는데_값이_없으면_빈_Map_값이_있으면_그_값() {
        assertThat(RuleStepHandler.hoursJson(List.of(term("임금", Map.of("type", "MONTHLY"), KeyTermState.OK))))
                .isNull();
        assertThat(RuleStepHandler.hoursJson(List.of(term("근로시간", null, KeyTermState.MISSING)))).isEmpty();
        assertThat(RuleStepHandler.hoursJson(List.of(term("소정 근로시간", Map.of("hoursPerDay", 4), KeyTermState.OK))))
                .containsEntry("hoursPerDay", 4);
    }

    @Test
    void extraction_json_최상위_wageOnlyDocument를_읽고_없거나_깨지면_false() {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(RuleStepHandler.wageOnlyDocument(om, "{\"wageOnlyDocument\": true}")).isTrue();
        assertThat(RuleStepHandler.wageOnlyDocument(om, "{\"wageOnlyDocument\": false}")).isFalse();
        assertThat(RuleStepHandler.wageOnlyDocument(om, "{\"clauses\": []}")).isFalse();
        assertThat(RuleStepHandler.wageOnlyDocument(om, null)).isFalse();
        assertThat(RuleStepHandler.wageOnlyDocument(om, "{깨짐")).isFalse();
    }
}
