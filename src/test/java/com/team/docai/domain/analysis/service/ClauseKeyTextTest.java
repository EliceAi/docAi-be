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
}
