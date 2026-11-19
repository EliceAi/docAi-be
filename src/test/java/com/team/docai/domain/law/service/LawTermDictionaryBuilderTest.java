package com.team.docai.domain.law.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.law.client.DailyTermLink;
import com.team.docai.domain.law.client.LawApiCache;
import com.team.docai.domain.law.client.LawApiClient;
import com.team.docai.domain.law.client.LawTermResponseParser;
import com.team.docai.domain.law.client.LawTestSupport;
import com.team.docai.domain.law.dto.DailyTermDictionaryFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 일상용어 사전 만들기: 캐시 사용, 거르는 기준, 상한, 파일 쓰기. API 는 모의 객체, 조문 수는 모의 검색 서비스. */
class LawTermDictionaryBuilderTest {

    @TempDir
    Path tmp;
    LawApiClient client;
    LawApiCache cache;
    LawSearchService search;
    LawTermDictionaryBuilder builder;

    @BeforeEach
    void setUp() {
        client = mock(LawApiClient.class);
        search = mock(LawSearchService.class);
        cache = new LawApiCache(tmp.resolve("law-cache"));
        builder = new LawTermDictionaryBuilder(client, cache, new LawTermResponseParser(new ObjectMapper()), search,
                new ObjectMapper());
        when(search.countCurrentArticles(anyString(), anyCollection())).thenReturn(5L);
    }

    @Test
    void 캐시가_없으면_부르고_저장하며_있으면_부르지_않는다() {
        when(client.fetchDailyTermLinks("알바")).thenReturn(LawTestSupport.resource("daily_term_alba.json"));
        cache.writeDailyTerm("복비", LawTestSupport.resource("daily_term_bokbi.json"));

        LawTermDictionaryBuilder.BuildReport report = builder.build(List.of("알바", "복비"), List.of(1L), Map.of(), false, false);

        verify(client).fetchDailyTermLinks("알바");
        verify(client, never()).fetchDailyTermLinks("복비");
        assertThat(cache.readDailyTerm("알바")).isPresent();
        assertThat(report.cacheHits()).isEqualTo(1);
        assertThat(report.dictionary().terms()).extracting(DailyTermDictionaryFile.Entry::daily).containsExactly("알바", "복비");
    }

    @Test
    void offline_인데_캐시가_없으면_실패하고_API_를_부르지_않는다() {
        assertThatThrownBy(() -> builder.build(List.of("알바"), List.of(1L), Map.of(), false, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("알바");
        verify(client, never()).fetchDailyTermLinks(any());
    }

    @Test
    void DB_에_없는_말_너무_흔한_말_사람이_뺀_연결을_거르고_용어관계_순으로_상한까지() {
        cache.writeDailyTerm("알바", LawTestSupport.resource("daily_term_alba.json"));
        // 단시간근로 6행, 단시간근로자 6행, 부업 0행, 종사자 999행, 종업원 4행(사람이 뺌)
        when(search.countCurrentArticles(eq("부업"), anyCollection())).thenReturn(0L);
        when(search.countCurrentArticles(eq("종사자"), anyCollection())).thenReturn((long) LawTermCatalog.MAX_ROWS + 1);

        DailyTermDictionaryFile file = builder.build(List.of("알바"), List.of(1L),
                Map.of("알바", Set.of("종업원")), false, true).dictionary();

        assertThat(file.terms()).hasSize(1);
        assertThat(file.terms().get(0).legal()).extracting(DailyTermDictionaryFile.Legal::term)
                .containsExactly("단시간근로");
        assertThat(file.rejected()).extracting(DailyTermDictionaryFile.Rejected::term, DailyTermDictionaryFile.Rejected::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("단시간근로자", "COVERED_BY_LEGAL"),
                        org.assertj.core.groups.Tuple.tuple("부업", "NOT_IN_LAW_DB"),
                        org.assertj.core.groups.Tuple.tuple("종사자", "TOO_COMMON"),
                        org.assertj.core.groups.Tuple.tuple("종업원", "BLOCKED_PAIR"));
    }

    @Test
    void 연계가_없는_일상용어는_noLinks_에_남긴다() {
        cache.writeDailyTerm("위약금", "{\"Law\": \"일치하는 일상용어가 없습니다.\"}");

        DailyTermDictionaryFile file = builder.build(List.of("위약금"), List.of(1L), Map.of(), false, true).dictionary();

        assertThat(file.terms()).isEmpty();
        assertThat(file.noLinks()).containsExactly("위약금");
    }

    @Test
    void 상한을_넘으면_동의어_하위어_상위어_연관어_순으로_남긴다() {
        cache.writeDailyTerm("월세", """
                {"dlytrmRltService": {"일상용어": {"연계용어": [
                  {"법령용어명": "임대료", "용어관계코드": "140305", "용어관계": "연관어"},
                  {"법령용어명": "차임액", "용어관계코드": "140303", "용어관계": "상위어"},
                  {"법령용어명": "월차임", "용어관계코드": "140301", "용어관계": "동의어"},
                  {"법령용어명": "선차임", "용어관계코드": "140304", "용어관계": "하위어"}
                ]}}}""");

        DailyTermDictionaryFile file = builder.build(List.of("월세"), List.of(1L), Map.of(), false, true).dictionary();

        assertThat(file.terms().get(0).legal()).extracting(DailyTermDictionaryFile.Legal::term)
                .containsExactly("월차임", "선차임", "차임액");
        assertThat(file.rejected()).extracting(DailyTermDictionaryFile.Rejected::reason).containsExactly("OVER_LIMIT");
    }

    @Test
    void 이미_고른_법령용어로_시작하는_말은_빼고_뒤에_온_짧은_말이_긴_말을_덮으면_짧은_말을_남긴다() {
        cache.writeDailyTerm("퇴직금", """
                {"dlytrmRltService": {"일상용어": {"연계용어": [
                  {"법령용어명": "퇴직급여", "용어관계코드": "140305", "용어관계": "연관어"},
                  {"법령용어명": "퇴직급여제도", "용어관계코드": "140303", "용어관계": "상위어"},
                  {"법령용어명": "퇴직급여금", "용어관계코드": "140301", "용어관계": "동의어"}
                ]}}}""");

        DailyTermDictionaryFile file = builder.build(List.of("퇴직금"), List.of(1L), Map.of(), false, true).dictionary();

        // 동의어 퇴직급여금 → 상위어 퇴직급여제도 → 연관어 퇴직급여(앞의 둘을 덮음) 순
        assertThat(file.terms().get(0).legal()).extracting(DailyTermDictionaryFile.Legal::term).containsExactly("퇴직급여");
        assertThat(file.rejected()).extracting(DailyTermDictionaryFile.Rejected::term, DailyTermDictionaryFile.Rejected::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("퇴직급여금", "COVERED_BY_LEGAL"),
                        org.assertj.core.groups.Tuple.tuple("퇴직급여제도", "COVERED_BY_LEGAL"));
    }

    @Test
    void DB_를_보지_않고_거르는_기준() {
        Map<String, Set<String>> blocked = Map.of("시급", Set.of("긴급"));
        assertThat(LawTermDictionaryBuilder.basicReject("정규직", new DailyTermLink("비정규직", DailyTermLink.ANTONYM, "반의어", null), blocked))
                .isEqualTo("ANTONYM");
        assertThat(LawTermDictionaryBuilder.basicReject("수습", new DailyTermLink("수 습", DailyTermLink.RELATED, "연관어", null), blocked))
                .isEqualTo("SAME_AS_DAILY");
        assertThat(LawTermDictionaryBuilder.basicReject("아르바이트", new DailyTermLink("일", DailyTermLink.RELATED, "연관어", null), blocked))
                .isEqualTo("TOO_SHORT");
        assertThat(LawTermDictionaryBuilder.basicReject("보증금", new DailyTermLink("보증금반환채권", DailyTermLink.RELATED, "연관어", null), blocked))
                .isEqualTo("COVERED_BY_DAILY");
        assertThat(LawTermDictionaryBuilder.basicReject("수습", new DailyTermLink("처리", DailyTermLink.RELATED, "연관어", null), blocked))
                .isEqualTo("GENERIC_WORD");
        assertThat(LawTermDictionaryBuilder.basicReject("시급", new DailyTermLink("긴급", DailyTermLink.RELATED, "연관어", null), blocked))
                .isEqualTo("BLOCKED_PAIR");
        assertThat(LawTermDictionaryBuilder.basicReject("월세", new DailyTermLink("차임", DailyTermLink.SYNONYM, "동의어", null), blocked))
                .isNull();
    }

    @Test
    void 사전_파일을_쓰고_서버가_다시_읽을_수_있다() throws Exception {
        cache.writeDailyTerm("복비", LawTestSupport.resource("daily_term_bokbi.json"));
        DailyTermDictionaryFile file = builder.build(List.of("복비"), List.of(1L), Map.of(), false, true).dictionary();
        Path out = tmp.resolve("out/daily-terms.json");

        builder.write(file, out);

        DailyTermDictionaryFile read = new ObjectMapper().readValue(Files.readString(out), DailyTermDictionaryFile.class);
        assertThat(read).isEqualTo(file);
        assertThat(Files.readString(out)).doesNotContain("OC=");
    }
}
