package com.team.docai.global.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextTruncatorTest {

    /** U+1F600, UTF-16 으로 2칸(서로게이트 쌍), 글자로는 1자 */
    static final String EMOJI = "😀";

    @Test
    void 한글은_글자_수대로_자르고_말줄임표를_붙이지_않는다() {
        assertThat(TextTruncator.truncate("가나다라마", 3)).isEqualTo("가나다");
        assertThat(TextTruncator.truncate("가나다", 3)).isEqualTo("가나다");
        assertThat(TextTruncator.truncate("가나", 3)).isEqualTo("가나");
    }

    @Test
    void 이모지는_한_글자로_세고_서로게이트_쌍을_반으로_자르지_않는다() {
        // "가😀나": UTF-16 길이 4, 글자 수 3
        String value = "가" + EMOJI + "나";

        assertThat(TextTruncator.truncate(value, 2)).isEqualTo("가" + EMOJI);
        assertThat(TextTruncator.truncate(value, 1)).isEqualTo("가");
        assertThat(TextTruncator.truncate(value, 3)).isEqualTo(value);
        // 이모지만 있을 때 1자 = 서로게이트 쌍 전체
        assertThat(TextTruncator.truncate(EMOJI + EMOJI, 1)).isEqualTo(EMOJI);
        // 끝이 짝 잃은 상위 서로게이트가 아님
        String cut = TextTruncator.truncate(value, 2);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
    }

    @Test
    void UTF16_길이가_한도를_넘어도_글자_수가_한도_이내면_그대로_둔다() {
        // 이모지 3개: length() 6, 글자 수 3
        String value = EMOJI.repeat(3);

        assertThat(TextTruncator.exceeds(value, 3)).isFalse();
        assertThat(TextTruncator.truncate(value, 3)).isSameAs(value);
        assertThat(TextTruncator.exceeds(value, 2)).isTrue();
    }

    @Test
    void null과_0과_음수() {
        assertThat(TextTruncator.truncate(null, 5)).isNull();
        assertThat(TextTruncator.exceeds(null, 0)).isFalse();
        assertThat(TextTruncator.truncate("가", 0)).isEmpty();
        assertThat(TextTruncator.truncate("", 0)).isEmpty();
        assertThatThrownBy(() -> TextTruncator.truncate("가", -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
