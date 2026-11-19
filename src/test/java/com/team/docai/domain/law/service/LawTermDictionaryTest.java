package com.team.docai.domain.law.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 일상용어 → 법령용어 검색어 넓히기 (원래 검색어 유지, 뒤에 더하기만). */
class LawTermDictionaryTest {

    static LawTermDictionary dictionary() {
        Map<String, List<String>> terms = new LinkedHashMap<>();
        terms.put("알바", List.of("단시간근로", "단시간근로자"));
        terms.put("월세", List.of("월차임", "차임"));
        terms.put("집주인", List.of("임대인"));
        terms.put("계약 해지", List.of("해지"));
        terms.put("퇴사", List.of("퇴직"));
        terms.put("사직", List.of("퇴직"));
        terms.put("빈값", List.of());
        return new LawTermDictionary(terms);
    }

    @Test
    void 원래_검색어를_앞에_그대로_두고_법령용어를_뒤에_더한다() {
        assertThat(dictionary().expand(List.of("알바", "수습")))
                .containsExactly("알바", "수습", "단시간근로", "단시간근로자");
    }

    @Test
    void 조사가_붙거나_낱말_앞부분이_일상용어면_맞고_가운데에_있으면_안_맞는다() {
        LawTermDictionary d = dictionary();
        assertThat(d.expand(List.of("월세를"))).containsExactly("월세를", "월차임", "차임");
        assertThat(d.expand(List.of("알바생 시급"))).containsExactly("알바생 시급", "단시간근로", "단시간근로자");
        assertThat(d.expand(List.of("선월세"))).containsExactly("선월세");
    }

    @Test
    void 띄어_쓴_일상용어는_붙여_쓴_검색어에도_맞는다() {
        assertThat(dictionary().expand(List.of("중도 계약해지"))).containsExactly("중도 계약해지", "해지");
        assertThat(dictionary().expand(List.of("계약 해지 위약금"))).containsExactly("계약 해지 위약금", "해지");
    }

    @Test
    void 이미_있는_말과_같은_법령용어는_다시_넣지_않는다() {
        assertThat(dictionary().expand(List.of("퇴사", "사직", "퇴직"))).containsExactly("퇴사", "사직", "퇴직");
        assertThat(dictionary().expand(List.of("집주인", "임대인"))).containsExactly("집주인", "임대인");
        assertThat(dictionary().expand(List.of("퇴사", "사직"))).containsExactly("퇴사", "사직", "퇴직");
    }

    @Test
    void 더하는_수는_상한까지() {
        Map<String, List<String>> terms = Map.of("가나", List.of("일", "이", "삼", "사", "오", "육", "칠"));
        assertThat(new LawTermDictionary(terms).expand(List.of("가나")))
                .hasSize(1 + LawTermDictionary.MAX_ADDED)
                .startsWith("가나");
    }

    @Test
    void 맞는_말이_없거나_빈_입력이면_원래_그대로() {
        LawTermDictionary d = dictionary();
        assertThat(d.expand(List.of("위약금", "교육비"))).containsExactly("위약금", "교육비");
        assertThat(d.expand(List.of())).isEmpty();
        assertThat(d.expand(null)).isEmpty();
        assertThat(d.expand(Arrays.asList(null, "알바"))).containsExactly("알바", "단시간근로", "단시간근로자");
        assertThat(d.size()).isEqualTo(6);
    }

    @Test
    void 서버_리소스의_사전을_읽는다() {
        LawTermDictionary d = new LawTermDictionary(new ObjectMapper(), LawTermDictionary.RESOURCE);

        assertThat(d.size()).isGreaterThanOrEqualTo(20);
        assertThat(d.expand(List.of("월세"))).contains("차임");
        assertThat(d.expand(List.of("세입자"))).contains("임차인");
        assertThat(d.expand(List.of("알바"))).contains("단시간근로");
        assertThat(d.expand(List.of("시급"))).doesNotContain("긴급");
        assertThat(d.added(List.of("퇴사"))).isEmpty();
    }

    @Test
    void 일상용어_뒤에는_흔한_접미사와_조사만_붙어야_맞는다() {
        LawTermDictionary d = new LawTermDictionary(Map.of(
                "시급", List.of("시간급"), "알바", List.of("단시간근로"), "보증금", List.of("차임"),
                "사장", List.of("사업주")));
        assertThat(d.added(List.of("사장님은"))).containsExactly("사업주");
        assertThat(d.added(List.of("사장실"))).isEmpty();
        assertThat(d.added(List.of("시급"))).containsExactly("시간급");
        assertThat(d.added(List.of("시급은"))).containsExactly("시간급");
        assertThat(d.added(List.of("시급으로는"))).containsExactly("시간급");
        assertThat(d.added(List.of("알바생도"))).containsExactly("단시간근로");
        assertThat(d.added(List.of("시급히"))).isEmpty();
        assertThat(d.added(List.of("시급하게"))).isEmpty();
        assertThat(d.added(List.of("보증금반환"))).isEmpty();
        assertThat(d.added(List.of("보증금 반환"))).containsExactly("차임");
    }

    @Test
    void added_는_더할_법령용어만_돌려준다() {
        assertThat(dictionary().added(List.of("알바", "위약금"))).containsExactly("단시간근로", "단시간근로자");
        assertThat(dictionary().added(List.of("위약금"))).isEmpty();
        assertThat(dictionary().added(null)).isEmpty();
    }

    @Test
    void 사전_파일이_없거나_깨졌으면_빈_사전으로_뜬다() {
        assertThat(new LawTermDictionary(new ObjectMapper(), "law/없는_사전.json").size()).isZero();
        assertThat(new LawTermDictionary(new ObjectMapper(), "law/daily_terms_broken.json").size()).isZero();
        assertThat(new LawTermDictionary(new ObjectMapper(), "law/daily_terms_wrong_shape.json").size()).isZero();
        assertThat(new LawTermDictionary(new ObjectMapper(), "law/daily_terms_broken.json").expand(List.of("알바")))
                .containsExactly("알바");
    }
}
