package com.team.docai.domain.analysis.service.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.CandidateSource;
import com.team.docai.domain.analysis.entity.ScheduleCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleCandidateBuilderTest {

    final ObjectMapper mapper = new ObjectMapper();

    static AnalysisClause clause(String key, int page, String articleNo) {
        return AnalysisClause.create(88L, key, articleNo, null, "원문", "한 줄", page, null, false, null, null,
                List.of(), List.of());
    }

    @Test
    void 계약_시작_종료_입주만_WRITTEN_후보로_만들고_같은_종류의_다른_날짜는_충돌로_둘_다_선택_안_함() throws Exception {
        String dates = """
                [{"label":"계약 시작일","date":"2026-12-01","clauseKey":"c1"},
                 {"label":"계약 종료일","date":"2027-02-28","clauseKey":"c1"},
                 {"label":"계약 만료일","date":"2027-03-31","clauseKey":"c9"},
                 {"label":"계약일","date":"2026-11-20","clauseKey":null},
                 {"label":"계약 시작일","date":"2026-12-01","clauseKey":"c1"},
                 {"label":"입주일","date":"bad","clauseKey":null}]
                """;
        ScheduleCandidateBuilder.Built built = ScheduleCandidateBuilder.build(88L, mapper.readTree(dates),
                Map.of("c1", clause("c1", 1, "제1조"), "c9", clause("c9", 2, null)));

        List<ScheduleCandidate> out = built.candidates();
        assertThat(out).extracting(ScheduleCandidate::getDateKind)
                .containsExactly("CONTRACT_START", "CONTRACT_END", "CONTRACT_END");
        assertThat(out).extracting(ScheduleCandidate::getSource).containsOnly(CandidateSource.WRITTEN);
        assertThat(out.get(0).getDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(out.get(0).getTitle()).isEqualTo("계약 시작일");
        assertThat(out.get(0).getLocation()).isEqualTo("1쪽 제1조");
        assertThat(out.get(0).getPageNo()).isEqualTo(1);
        assertThat(out.get(0).isDefaultSelected()).isTrue();
        assertThat(out.get(0).getConflictGroup()).isNull();
        assertThat(out.get(1).getConflictGroup()).isEqualTo("CONTRACT_END");
        assertThat(out.get(2).getConflictGroup()).isEqualTo("CONTRACT_END");
        assertThat(out.get(1).isDefaultSelected()).isFalse();
        assertThat(out.get(2).isDefaultSelected()).isFalse();
        assertThat(out.get(2).getLocation()).isEqualTo("2쪽");
        assertThat(built.log()).hasSize(1);
        assertThat(built.log().get(0).get("type")).isEqualTo("DATE_KIND_UNKNOWN");
    }

    @Test
    void 날짜가_없으면_후보도_없다() {
        assertThat(ScheduleCandidateBuilder.build(88L, mapper.missingNode(), Map.of()).candidates()).isEmpty();
    }

    @Test
    void 이름으로_종류를_고른다() {
        assertThat(ScheduleCandidateBuilder.kindOf("근로계약 시작일")).isEqualTo("CONTRACT_START");
        assertThat(ScheduleCandidateBuilder.kindOf("첫 출근")).isEqualTo("CONTRACT_START");
        assertThat(ScheduleCandidateBuilder.kindOf("임대차 종료")).isEqualTo("CONTRACT_END");
        assertThat(ScheduleCandidateBuilder.kindOf("입주 예정일")).isEqualTo("MOVE_IN");
        assertThat(ScheduleCandidateBuilder.kindOf("잔금일")).isNull();
        assertThat(ScheduleCandidateBuilder.kindOf("계약일")).isNull();
    }
}
