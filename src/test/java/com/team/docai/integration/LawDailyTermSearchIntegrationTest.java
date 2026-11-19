package com.team.docai.integration;

import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.service.LawSearchService;
import com.team.docai.domain.law.service.LawTermDictionary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적재된 운영 법령(시행령 2개 추가분 포함)과 서버 리소스의 일상용어 사전으로 검색을 확인한다. 읽기만 하고 행을 만들거나 지우지 않는다.
 * 법령이 적재되지 않은 DB 에서는 실패한다 (로컬 DB 기준 테스트).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class LawDailyTermSearchIntegrationTest {

    @Autowired LawSearchService lawSearchService;
    @Autowired LawTermDictionary lawTermDictionary;
    @Autowired JdbcTemplate jdbc;

    static String label(LawArticleInfo a) {
        return a.lawName() + " " + a.articleNo() + "-" + a.articleBranchNo() + "-" + (a.paragraphNo() == null ? 0 : a.paragraphNo());
    }

    @Test
    void 최저임금법_시행령_제3조_수습_감액이_적재되고_상위_법령과_근로계약서_관련_법령에_연결된다() {
        List<LawArticleInfo> art3 = lawSearchService.findArticles("최저임금법 시행령", "제3조", null);
        assertThat(art3).isNotEmpty();
        assertThat(art3.get(0).title()).contains("수습");
        assertThat(art3.get(0).content()).contains("100분의 10");

        Long decree = lawSearchService.resolveLawId("최저임금법 시행령").orElseThrow();
        Long act = lawSearchService.resolveLawId("최저임금법").orElseThrow();
        Long retireDecree = lawSearchService.resolveLawId("근로자퇴직급여 보장법 시행령").orElseThrow();
        assertThat(jdbc.queryForObject("SELECT parent_law_id FROM law WHERE law_id = ?", Long.class, decree)).isEqualTo(act);
        assertThat(lawSearchService.findLawIds("EMPLOYMENT")).contains(decree, retireDecree);
        assertThat(jdbc.queryForObject("SELECT core FROM document_type_law WHERE document_type_code = 'EMPLOYMENT' AND law_id = ?",
                Boolean.class, decree)).isFalse();
    }

    @Test
    void 수습_최저임금_검색이_최저임금법_제5조_제2항이나_시행령_제3조를_찾는다() {
        List<Long> employment = lawSearchService.findLawIds("EMPLOYMENT");

        List<String> hits = lawSearchService.searchWithDailyTerms(List.of("수습", "최저임금"), employment, null, 5).stream()
                .map(LawDailyTermSearchIntegrationTest::label).toList();

        assertThat(hits).containsAnyOf("최저임금법 5-0-2", "최저임금법 시행령 3-0-0");
        assertThat(hits).contains("최저임금법 시행령 3-0-0");
    }

    @Test
    void 일상용어만_있어_원래_검색어로_못_찾던_조문을_법령용어로_찾는다() {
        List<Long> employment = lawSearchService.findLawIds("EMPLOYMENT");

        List<LawArticleInfo> plain = lawSearchService.search(List.of("알바"), employment, null, 5);
        List<LawArticleInfo> widened = lawSearchService.searchWithDailyTerms(List.of("알바"), employment, null, 5);

        assertThat(plain).isEmpty();
        assertThat(widened).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(widened).allSatisfy(a -> assertThat(a.title() + " " + a.content()).contains("단시간근로"));
    }

    @Test
    void 세입자_집주인_같은_말로_임대차_조문을_찾는다() {
        List<Long> lease = lawSearchService.findLawIds("LEASE");

        List<LawArticleInfo> widened = lawSearchService.searchWithDailyTerms(List.of("세입자", "집주인", "월세"), lease, null, 5);

        assertThat(widened).hasSize(5);
        assertThat(widened).allSatisfy(a -> assertThat(a.content()).containsAnyOf("임차인", "임대인", "차임"));
    }

    @Test
    void 원래_검색어로_찾은_조문과_순서는_그대로_두고_사전에_없는_말은_넓히지_않는다() {
        List<Long> employment = lawSearchService.findLawIds("EMPLOYMENT");
        List<String> keywords = List.of("퇴사", "위약금");

        List<LawArticleInfo> plain = lawSearchService.search(keywords, employment, null, 5);
        List<LawArticleInfo> widened = lawSearchService.searchWithDailyTerms(keywords, employment, null, 5);

        // 퇴사 → 퇴직은 퇴직연금 조문만 채워서 뺐다 (BLOCKED_PAIRS). 원래 결과(근로기준법 제20조)만 남는다
        assertThat(plain).extracting(LawDailyTermSearchIntegrationTest::label).contains("근로기준법 20-0-0");
        assertThat(lawTermDictionary.added(keywords)).isEmpty();
        assertThat(widened).isEqualTo(plain);
    }

    @Test
    void 넓혀도_원래_결과가_앞에_그대로_있고_채운_조문은_법령용어가_들어간_조문이다() {
        List<Long> lease = lawSearchService.findLawIds("LEASE");
        List<String> keywords = List.of("월세", "연체");

        List<LawArticleInfo> plain = lawSearchService.search(keywords, lease, null, 5);
        List<LawArticleInfo> widened = lawSearchService.searchWithDailyTerms(keywords, lease, null, 5);

        assertThat(widened.subList(0, plain.size())).isEqualTo(plain);
        assertThat(widened).hasSizeLessThanOrEqualTo(5);
        assertThat(widened.subList(plain.size(), widened.size()))
                .allSatisfy(a -> assertThat(a.content()).containsAnyOf("차임", "연체"));
    }
}
