package com.team.docai.domain.law.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 2026-11-19 실제 법제처 일상용어-법령용어 연계 응답(인증값 가림)으로 파서를 확인한다. */
class LawTermResponseParserTest {

    LawTermResponseParser parser = new LawTermResponseParser(new ObjectMapper());

    @Test
    void 연계용어_배열을_순서대로_읽고_링크는_버린다() {
        List<DailyTermLink> links = parser.parse(LawTestSupport.resource("daily_term_alba.json"));

        assertThat(links).extracting(DailyTermLink::legalTerm)
                .containsExactly("단시간근로", "단시간근로자", "부업", "종사자", "종업원");
        assertThat(links.get(0)).isEqualTo(new DailyTermLink("단시간근로", DailyTermLink.SYNONYM, "동의어", null));
        assertThat(links.get(3).relationCode()).isEqualTo(DailyTermLink.BROADER);
        assertThat(links.toString()).doesNotContain("OC=", "DRF");
    }

    @Test
    void 연계용어가_1건이면_객체로_와도_읽는다() {
        List<DailyTermLink> links = parser.parse(LawTestSupport.resource("daily_term_bokbi.json"));

        assertThat(links).containsExactly(new DailyTermLink("중개수수료", DailyTermLink.BROADER, "상위어", null));
    }

    @Test
    void 일치하는_일상용어가_없으면_빈_목록() {
        assertThat(parser.parse("{\"Law\": \"일치하는 일상용어가 없습니다. 검색조건을 확인하여 주십시오.\"}")).isEmpty();
        assertThat(parser.parse("{\"dlytrmRltService\": {\"검색결과개수\": \"0\"}}")).isEmpty();
    }

    @Test
    void 일상용어가_여러_개면_모두_읽고_같은_법령용어는_한_번만() {
        String json = """
                {"dlytrmRltService": {"일상용어": [
                  {"일상용어명": "월세", "연계용어": [{"법령용어명": "차임", "용어관계코드": "140301", "용어관계": "동의어", "비고": "임대차"}]},
                  {"일상용어명": "월세", "연계용어": {"법령용어명": "차임", "용어관계코드": "140305", "용어관계": "연관어"}},
                  {"일상용어명": "월세", "연계용어": {"법령용어명": " ", "용어관계코드": "140305"}}
                ]}}""";

        assertThat(parser.parse(json)).containsExactly(new DailyTermLink("차임", "140301", "동의어", "임대차"));
    }

    @Test
    void 다른_모양이면_예외() {
        assertThatThrownBy(() -> parser.parse("{\"LawSearch\": {}}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("<xml/>")).isInstanceOf(IllegalArgumentException.class);
    }
}
