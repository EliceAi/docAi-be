package com.team.docai.domain.analysis.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisEntityTest {

    @Test
    void 분석은_ANALYZING_READING_TEXT로_시작한다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-17T10:00:00+09:00");

        Analysis a = Analysis.create(7L, "EMPLOYMENT", "extract-v1/judge-v1", now);

        assertThat(a.getStatus()).isEqualTo(AnalysisStatus.ANALYZING);
        assertThat(a.getProgressStep()).isEqualTo(AnalysisStep.READING_TEXT);
        assertThat(a.getStartedAt()).isEqualTo(now);
        assertThat(a.getHeartbeatAt()).isEqualTo(now);
        assertThat(a.getCompletedAt()).isNull();
        assertThat(a.getFailReason()).isNull();
    }

    @Test
    void 조항은_판정중_제외안함으로_만든다() {
        AnalysisClause c = AnalysisClause.create(1L, "c7", "제7조", "교육비 반환", "근로자가 ...", "교육비 반환",
                2, "2쪽 특약", true, "퇴사하면 돈을 물어냄", null,
                List.of(new LawHint("근로기준법", "제20조")), List.of("위약금"));

        assertThat(c.getVerdict()).isEqualTo(ClauseVerdict.JUDGING);
        assertThat(c.isExcluded()).isFalse();
    }

    @Test
    void 원문을_읽지_못한_조항은_UNREADABLE로_만든다() {
        AnalysisClause c = AnalysisClause.create(1L, "c2", null, null, null, null, 2, null, false, null, null, null, null);

        assertThat(c.getVerdict()).isEqualTo(ClauseVerdict.UNREADABLE);
        assertThat(c.isExcluded()).isFalse();
    }

    @Test
    void 묶음은_PENDING_재시도_0으로_만든다() {
        AnalysisChunk chunk = AnalysisChunk.create(1L, 1, 1, 5);

        assertThat(chunk.getStatus()).isEqualTo(ChunkStatus.PENDING);
        assertThat(chunk.getRetryCount()).isZero();
    }

    @Test
    void 근거는_조문과_해석메모_중_하나만() {
        assertThatThrownBy(() -> AnalysisClauseRef.create(1L, "L1", 10L, 20L, RefFoundBy.TAG_MAP, false, "t", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisClauseRef.create(1L, "L1", null, null, RefFoundBy.TAG_MAP, false, "t", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AnalysisClauseRef.create(1L, "G1", null, 20L, RefFoundBy.TAG_MAP, false, "t", "x").getGuidanceId())
                .isEqualTo(20L);
    }

    @Test
    void 관련_조항은_자기_자신과_연결하지_않는다() {
        assertThatThrownBy(() -> AnalysisClauseRelation.create(1L, 5L, 5L)).isInstanceOf(IllegalArgumentException.class);
    }
}
