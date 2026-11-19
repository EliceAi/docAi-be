package com.team.docai.domain.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 문장 나누기·단정 표현 문장 지우기 (2차 판정과 챗봇이 같이 쓴다) */
class AssertionTextTest {

    static String remove(String text) {
        return AssertionText.removeSentences(text, (found, sentence) -> { });
    }

    @Test
    void 숫자_사이_마침표는_나누지_않고_붙은_마침표는_나눈다() {
        assertThat(AssertionText.split("시작일은 2026.11.12예요. 인상률은 10.5%예요.있어요.그리고 끝"))
                .extracting(AssertionText.Sentence::text)
                .containsExactly("시작일은 2026.11.12예요.", "인상률은 10.5%예요.", "있어요.", "그리고 끝");
        assertThat(remove("확인이 필요해요.그리고 이 조항은 무효예요.")).isEqualTo("확인이 필요해요.");
    }

    @Test
    void 닫는_따옴표_괄호까지_한_문장이다() {
        assertThat(AssertionText.split("상대가 \"서명해도 돼요.\" 라고 했어요. (참고하세요.) 끝이에요!」 다음"))
                .extracting(AssertionText.Sentence::text)
                .containsExactly("상대가 \"서명해도 돼요.\"", "라고 했어요.", "(참고하세요.)", "끝이에요!」", "다음");
    }

    @Test
    void 지운_뒤_번호_조각만_남으면_버리고_줄바꿈은_줄바꿈으로_잇는다() {
        assertThat(remove("1. 이 조항은 무효예요.\n2. 교육비를 확인하세요.")).isEqualTo("2. 교육비를 확인하세요.");
        assertThat(remove("첫 줄이에요.\n이 조항은 불법이에요.\n셋째 줄이에요. 넷째예요."))
                .isEqualTo("첫 줄이에요.\n셋째 줄이에요. 넷째예요.");
        assertThat(remove("앞 문장이에요. 이 조항은 무효예요. 뒤 문장이에요.")).isEqualTo("앞 문장이에요. 뒤 문장이에요.");
    }

    @Test
    void 지운_것이_없으면_원래_글_다_지우면_null() {
        assertThat(remove("  2026.11.12 시작이에요.\n끝.  ")).isEqualTo("2026.11.12 시작이에요.\n끝.");
        assertThat(remove("무효예요. 불법이에요.")).isNull();
        List<String> found = new ArrayList<>();
        AssertionText.removeSentences("계약은 위반입니다. 괜찮아요.", (f, s) -> found.add(f + "|" + s));
        assertThat(found).containsExactly("위반입니다|계약은 위반입니다.");
    }

    @Test
    void 지운_문장이_사이에_없는_나눔은_원래_공백을_그대로_쓴다() {
        assertThat(remove("계약은 2026. 11. 12.부터 시작해요. 이 위약금 조항은 무효예요."))
                .isEqualTo("계약은 2026. 11. 12.부터 시작해요.");
        assertThat(remove("2026. 3. 1.~2027. 2. 28.이에요. 무효예요."))
                .isEqualTo("2026. 3. 1.~2027. 2. 28.이에요.");
    }

    @Test
    void 번호_조각은_줄_처음일_때만_버리고_줄_중간_날짜는_통째로_남긴다() {
        assertThat(remove("시급을 확인하세요. 2026. 11. 12. 체결된 이 조항은 무효예요."))
                .isEqualTo("시급을 확인하세요. 2026. 11. 12.");
        assertThat(remove("1. 시급 확인\n2. 무효 조항")).isEqualTo("1. 시급 확인");
    }
}
