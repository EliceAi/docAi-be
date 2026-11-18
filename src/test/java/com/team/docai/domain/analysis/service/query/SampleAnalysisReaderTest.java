package com.team.docai.domain.analysis.service.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.entity.BasisType;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.dto.AnalysisResultInfo;
import com.team.docai.domain.analysis.dto.ClauseDetailInfo;
import com.team.docai.domain.analysis.dto.SampleAnalysisInfo;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예시 분석 고정 JSON(analysis-samples/EMPLOYMENT.json)이 응답 모양에 맞고, 결과와 조항 상세가 서로 맞는지 본다.
 * 근로계약서 예시는 테스트_정답세트 01_근로_정상 (조항 11개 모두 OK, 주요 조건 8개) 기준.
 */
class SampleAnalysisReaderTest {

    final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    final SampleAnalysisReader reader = new SampleAnalysisReader(mapper);

    @Test
    void 근로계약서_예시를_읽는다() {
        SampleAnalysisInfo sample = reader.find("EMPLOYMENT").orElseThrow();
        AnalysisResultInfo.Result r = sample.result();

        assertThat(r.documentTypeCode()).isEqualTo("EMPLOYMENT");
        assertThat(r.analyzedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
        assertThat(r.keyTerms()).hasSize(8);
        assertThat(r.clauses()).hasSize(11);
        assertThat(r.counts().totalClauses()).isEqualTo(r.clauses().size());
        assertThat(r.counts().needsCheck()).isEqualTo(r.clauses().stream()
                .filter(c -> c.verdict() == ClauseVerdict.NEEDS_CHECK || c.verdict() == ClauseVerdict.UNCERTAIN).count());
        assertThat(r.counts().missing()).isEqualTo(r.missingItems().size());
        assertThat(r.counts().deadline()).isEqualTo(r.deadlines().size());
        assertThat(r.clauses()).noneMatch(c -> c.verdict() == ClauseVerdict.JUDGING);
        assertThat(r.disclaimer()).isEqualTo(AnalysisQueryService.DISCLAIMER);
        assertThat(r.coverage().readPages()).containsExactly(1, 2);
    }

    @Test
    void 결과의_조항마다_같은_ID의_조항_상세가_있고_값이_맞다() {
        SampleAnalysisInfo sample = reader.find("EMPLOYMENT").orElseThrow();

        List<Long> resultIds = sample.result().clauses().stream().map(AnalysisResultInfo.ClauseSummary::clauseId).toList();
        assertThat(sample.clauseDetails().keySet()).containsExactlyElementsOf(resultIds);
        for (AnalysisResultInfo.ClauseSummary c : sample.result().clauses()) {
            ClauseDetailInfo d = sample.clauseDetails().get(c.clauseId());
            assertThat(d.clauseId()).isEqualTo(c.clauseId());
            assertThat(d.articleNo()).isEqualTo(c.articleNo());
            assertThat(d.title()).isEqualTo(c.title());
            assertThat(d.verdict()).isEqualTo(c.verdict());
            assertThat(d.original().pageNo()).isEqualTo(c.pageNo());
            assertThat(d.original().text()).isNotBlank();
            assertThat(d.oneLine()).isNotBlank();
            assertThat(d.easyExplanation()).isNotBlank();
            if (d.verdict() == ClauseVerdict.OK) {
                assertThat(d.whyCheck()).isNull();
                assertThat(d.issueSummary()).isNull();
            }
        }
    }

    @Test
    void 조항_상세의_빈_basisType은_AI_INTERPRETATION으로_채운다() {
        SampleAnalysisInfo sample = reader.find("EMPLOYMENT").orElseThrow();

        assertThat(sample.clauseDetails().values())
                .allSatisfy(d -> assertThat(d.basisType()).isEqualTo(BasisType.AI_INTERPRETATION));
    }

    @Test
    void 예시_문장에_개인정보가_없다() {
        SampleAnalysisInfo sample = reader.find("EMPLOYMENT").orElseThrow();
        String all = sample.toString();
        assertThat(all).doesNotContain("최서연", "박정민", "010-", "02-000", "은평구");
    }

    @Test
    void 예시가_없거나_형식이_틀린_코드는_empty() {
        assertThat(reader.find("LEASE")).isEmpty();
        assertThat(reader.find("employment")).isEmpty();
        assertThat(reader.find("../application")).isEmpty();
        assertThat(reader.find("")).isEmpty();
        assertThat(reader.find(null)).isEmpty();
    }

    @Test
    void 같은_코드는_한_번만_읽는다() {
        assertThat(reader.find("EMPLOYMENT").orElseThrow()).isSameAs(reader.find("EMPLOYMENT").orElseThrow());
    }
}
