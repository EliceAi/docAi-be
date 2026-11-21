package com.team.docai.domain.law.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LawArticleInfoTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 22);
    static final String UPCOMING = "[시행 예정 조문(2027-04-28 시행)으로 대신함: 지금 적용되는 조문과 다를 수 있음]\n";

    static LawArticleInfo article(LocalDate from, boolean fallback) {
        return new LawArticleInfo(1L, 1L, "민간임대주택에 관한 특별법 시행령", 34, 0, null, "제목", "본문", from, null, null, fallback);
    }

    @Test
    void 시행일이_오늘보다_뒤인_대체_행은_시행_예정_표시() {
        assertThat(article(LocalDate.of(2027, 4, 28), true).substituteNote(LocalDate.of(2026, 11, 20), TODAY))
                .isEqualTo(UPCOMING);
    }

    @Test
    void 기준일_없이_오늘_기준이면_시행일이_오늘보다_뒤인_행만_시행_예정_표시() {
        assertThat(article(LocalDate.of(2027, 4, 28), false).substituteNote(null, TODAY)).isEqualTo(UPCOMING);
        assertThat(article(LocalDate.of(2025, 10, 1), false).substituteNote(null, TODAY)).isEmpty();
        assertThat(article(TODAY, false).substituteNote(null, TODAY)).isEmpty();
    }

    @Test
    void 기준일보다_뒤지만_오늘_시행_중인_대체_행은_현행_조문_표시() {
        assertThat(article(LocalDate.of(2026, 10, 8), false).substituteNote(LocalDate.of(2025, 3, 1), TODAY))
                .isEqualTo(LawArticleInfo.CURRENT_SUBSTITUTE_NOTE);
        assertThat(article(LocalDate.of(2020, 1, 1), true).substituteNote(TODAY, TODAY))
                .isEqualTo(LawArticleInfo.CURRENT_SUBSTITUTE_NOTE);
        // 기준일 < 시행일 = 오늘: 오늘은 시행 중이라 시행 예정이 아니라 현행 표시
        assertThat(article(TODAY, false).substituteNote(LocalDate.of(2026, 11, 1), TODAY))
                .isEqualTo(LawArticleInfo.CURRENT_SUBSTITUTE_NOTE);
    }

    @Test
    void 기준일에_시행_중인_행은_표시하지_않는다() {
        assertThat(article(LocalDate.of(2025, 10, 1), false).substituteNote(LocalDate.of(2026, 11, 20), TODAY)).isEmpty();
        // 계약일이 시행 예정일 뒤인 계약: 그날 시행 중이므로 대체가 아님
        assertThat(article(LocalDate.of(2027, 4, 28), false).substituteNote(LocalDate.of(2027, 5, 1), TODAY)).isEmpty();
    }
}
