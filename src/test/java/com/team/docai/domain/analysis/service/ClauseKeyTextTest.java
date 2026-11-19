package com.team.docai.domain.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClauseKeyTextTest {

    static String replace(String text, Map<String, String> names, List<String> log) {
        return ClauseKeyText.replace(text, names::get, (f, r) -> log.add(f + "→" + r));
    }

    @Test
    void 숫자는_정수로_맞춰_찾고_없는_키와_너무_큰_수는_그대로_둔다() {
        List<String> log = new ArrayList<>();
        assertThat(replace("c05, c007, c99, c99999999999, C1 등급", Map.of("c5", "제5조", "c7", "제7조"), log))
                .isEqualTo("제5조, 제7조, c99, c99999999999, C1 등급");
        assertThat(log).containsExactly("c05→제5조", "c007→제7조");
    }

    @Test
    void 바꿀_것이_없으면_같은_글을_돌려준다() {
        String text = "키가 없는 글이에요. contract c";
        assertThat(replace(text, Map.of("c1", "제1조"), new ArrayList<>())).isSameAs(text);
        assertThat(replace(null, Map.of(), new ArrayList<>())).isNull();
    }

    @Test
    void 바꾼_이름의_받침에_맞춰_바로_뒤_조사를_고친다() {
        Map<String, String> names = Map.of("c7", "제7조", "c1", "「임금」 조항", "c3", "제10조의2", "c5", "「계약 기간」 조항");
        assertThat(replace("c7은 c7이 c7을 c7과 c7으로", names, new ArrayList<>()))
                .isEqualTo("제7조는 제7조가 제7조를 제7조와 제7조로");
        assertThat(replace("c1는 c1가 c1를 c1와 c1로", names, new ArrayList<>()))
                .isEqualTo("「임금」 조항은 「임금」 조항이 「임금」 조항을 「임금」 조항과 「임금」 조항으로");
        // 숫자로 끝나는 이름은 읽는 소리로 (2 = 이, 받침 없음), 뒤에 한글이 더 붙으면 조사로 보지 않음
        assertThat(replace("c3은 c7이다 c7에서 c5조항이", names, new ArrayList<>()))
                .isEqualTo("제10조의2는 제7조이다 제7조에서 「계약 기간」 조항이");
    }

    @Test
    void 숫자로_끝나는_이름은_숫자_읽는_소리로_받침을_정한다() {
        for (String[] c : new String[][]{
                {"제1조의1", "c1은 c1으로", "제1조의1은 제1조의1로"},       // 일: ㄹ 받침
                {"제1조의3", "c1은 c1로", "제1조의3은 제1조의3으로"},       // 삼: 받침
                {"제1조의7", "c1이 c1을", "제1조의7이 제1조의7을"},         // 칠: ㄹ 받침
                {"제1조의0", "c1와", "제1조의0과"},                       // 영: 받침
                {"제1조의4", "c1은 c1이 c1을 c1과 c1으로", "제1조의4는 제1조의4가 제1조의4를 제1조의4와 제1조의4로"},
                {"제1조의9", "c1은", "제1조의9는"},
        }) {
            assertThat(replace(c[1], Map.of("c1", c[0]), new ArrayList<>())).as(c[0]).isEqualTo(c[2]);
        }
    }

    @Test
    void 조사_뒤에_의_요_는_도_만이_붙어도_맞추고_띄어_쓴_조도_겹치지_않게() {
        Map<String, String> names = Map.of("c7", "제7조");
        assertThat(replace("c7은요 c7과의 c7을도 c7이만", names, new ArrayList<>()))
                .isEqualTo("제7조는요 제7조와의 제7조를도 제7조가만");
        assertThat(replace("c7 조는 c7 조항은", names, new ArrayList<>())).isEqualTo("제7조는 제7조 조항은");
        // 조사로 볼 수 없는 것은 그대로
        assertThat(replace("c7이다 c7이요 c7 조정", names, new ArrayList<>())).isEqualTo("제7조이다 제7조이요 제7조 조정");
    }
}
