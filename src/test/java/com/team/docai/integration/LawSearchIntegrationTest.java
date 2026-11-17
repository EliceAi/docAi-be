package com.team.docai.integration;

import com.team.docai.domain.law.client.LawListItem;
import com.team.docai.domain.law.client.ParsedArticle;
import com.team.docai.domain.law.client.ParsedLaw;
import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.dto.RuleSeed;
import com.team.docai.domain.law.entity.AliasSource;
import com.team.docai.domain.law.service.LawSearchService;
import com.team.docai.domain.law.service.LawWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 법령 저장(LawWriter)과 조문 검색(LawSearchService)을 실제 PostgreSQL 로 확인한다 (search_vector, 시행 기간).
 * 테스트 법령은 고유한 이름·법령ID(T + 난수)로 만들고 @AfterEach 에서 그 법령의 행만 지운다.
 * 적재기로 넣은 운영 법령 행은 건드리지 않는다 (검색은 테스트 법령 ID 로 범위를 좁힌다).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class LawSearchIntegrationTest {

    @Autowired LawWriter lawWriter;
    @Autowired LawSearchService lawSearchService;
    @Autowired JdbcTemplate jdbc;

    String suffix;
    String lawName;
    String sourceLawId;
    String ruleCode;
    final List<Long> createdLawIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        suffix = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        lawName = "테스트 위약보호법 " + suffix;
        sourceLawId = "T" + suffix;
        ruleCode = "TEST_RULE_" + suffix;
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM rule_param WHERE rule_code = ?", ruleCode);
        for (Long lawId : createdLawIds) {
            jdbc.update("DELETE FROM document_type_law WHERE law_id = ?", lawId);
            jdbc.update("DELETE FROM law_alias WHERE law_id = ?", lawId);
            jdbc.update("DELETE FROM law_article WHERE law_id = ?", lawId);
            jdbc.update("DELETE FROM law WHERE law_id = ?", lawId);
        }
    }

    LawListItem item(String mst, LocalDate effective) {
        return new LawListItem(mst, sourceLawId, lawName, null, "법률", "현행", effective.minusMonths(1), effective);
    }

    ParsedLaw parsed(LocalDate effective, List<ParsedArticle> articles) {
        return new ParsedLaw(sourceLawId, lawName, "법률", null, effective.minusMonths(1), effective, articles, 0, 0, 0, 0);
    }

    Long saveOldAndNewVersions() {
        LocalDate oldFrom = LocalDate.of(2020, 1, 1);
        LawWriter.SaveResult old = lawWriter.saveVersion(item("M1" + suffix, oldFrom), parsed(oldFrom, List.of(
                new ParsedArticle(10, 0, null, "위약 예정", "제10조(위약 예정) 옛 조문: 위약금을 정하지 못한다.", oldFrom))),
                OffsetDateTime.now());
        createdLawIds.add(old.lawId());

        LocalDate newFrom = LocalDate.of(2026, 1, 1);
        LawWriter.SaveResult cur = lawWriter.saveVersion(item("M2" + suffix, newFrom), parsed(newFrom, List.of(
                new ParsedArticle(10, 0, null, "위약 예정", "제10조(위약 예정) 사용자는 위약금을 미리 정하는 계약을 체결하지 못한다.", newFrom),
                new ParsedArticle(10, 2, 1, "교육비 반환", "① 교육비 반환 약정은 실제 비용 범위에서만 할 수 있다.", newFrom),
                new ParsedArticle(10, 2, 2, "교육비 반환", "② 의무 재직 기간은 합리적이어야 한다.", newFrom),
                new ParsedArticle(11, 0, null, "임금", "제11조(임금) 임금은 통화로 지급한다.", newFrom))),
                OffsetDateTime.now());
        assertThat(cur.lawId()).isEqualTo(old.lawId());
        assertThat(cur.inserted()).isEqualTo(4);
        assertThat(cur.closed()).isEqualTo(1);
        return cur.lawId();
    }

    @Test
    void 새_개정본을_저장하면_이전_행을_새_시행일_전날로_닫고_가지번호_기본값은_0() {
        Long lawId = saveOldAndNewVersions();

        Map<String, Object> old = jdbc.queryForMap(
                "SELECT effective_to, article_branch_no FROM law_article WHERE law_id = ? AND source_mst = ?", lawId, "M1" + suffix);
        assertThat(old.get("effective_to").toString()).isEqualTo("2025-12-31");
        assertThat(old.get("article_branch_no")).isEqualTo(0);
        Integer open = jdbc.queryForObject(
                "SELECT count(*) FROM law_article WHERE law_id = ? AND effective_to IS NULL", Integer.class, lawId);
        assertThat(open).isEqualTo(4);
    }

    @Test
    void 같은_개정본을_다시_저장하면_행을_늘리지_않고_내용만_고친다() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LawWriter.SaveResult first = lawWriter.saveVersion(item("M3" + suffix, from), parsed(from, List.of(
                new ParsedArticle(5, 0, null, "가", "제5조 처음", from))), OffsetDateTime.now());
        createdLawIds.add(first.lawId());

        LawWriter.SaveResult again = lawWriter.saveVersion(item("M3" + suffix, from), parsed(from, List.of(
                new ParsedArticle(5, 0, null, "가", "제5조 고침", from))), OffsetDateTime.now());

        assertThat(again.inserted()).isZero();
        assertThat(again.updated()).isEqualTo(1);
        assertThat(lawWriter.isLoaded(item("M3" + suffix, from))).isTrue();
        assertThat(jdbc.queryForObject("SELECT content FROM law_article WHERE law_id = ?", String.class, first.lawId()))
                .isEqualTo("제5조 고침");
    }

    @Test
    void 이름_띄어쓰기와_약칭으로_법령을_찾는다() {
        Long lawId = saveOldAndNewVersions();
        String alias = "테위법" + suffix;
        lawWriter.upsertAlias(alias, lawName, AliasSource.MANUAL);

        assertThat(lawSearchService.resolveLawId(lawName.replace(" ", ""))).contains(lawId);
        assertThat(lawSearchService.resolveLawId(alias)).contains(lawId);
        assertThat(lawSearchService.resolveLawId("없는법" + suffix)).isEmpty();
    }

    @Test
    void 조문_번호로_찾으면_가지번호를_구분하고_항을_좁힌다() {
        Long lawId = saveOldAndNewVersions();
        LocalDate d = LocalDate.of(2026, 6, 1);

        List<LawArticleInfo> branch = lawSearchService.findArticles(lawName, "제10조의2", d);
        assertThat(branch).extracting(LawArticleInfo::paragraphNo).containsExactly(1, 2);
        assertThat(branch.get(0).label()).isEqualTo(lawName + " 제10조의2제1항");
        assertThat(branch.get(0).lawId()).isEqualTo(lawId);

        assertThat(lawSearchService.findArticles(lawName, "제10조의2 제2항", d))
                .singleElement().satisfies(a -> assertThat(a.content()).startsWith("②"));
        assertThat(lawSearchService.findArticles(lawName, "제10조", d))
                .singleElement().satisfies(a -> assertThat(a.content()).contains("미리 정하는"));
        assertThat(lawSearchService.findArticles(lawName, "제99조", d)).isEmpty();
    }

    @Test
    void 기준일에_유효한_개정본을_고르고_가장_옛_버전보다_앞서면_가장_가까운_버전으로_대체를_표시한다() {
        Long lawId = saveOldAndNewVersions();
        com.team.docai.domain.law.dto.ArticleNumber art10 = new com.team.docai.domain.law.dto.ArticleNumber(10, 0, null);
        com.team.docai.domain.law.dto.ArticleNumber art11 = new com.team.docai.domain.law.dto.ArticleNumber(11, 0, null);

        assertThat(lawSearchService.findArticles(lawId, art10, LocalDate.of(2024, 5, 1)))
                .singleElement().satisfies(a -> {
                    assertThat(a.content()).contains("옛 조문");
                    assertThat(a.fallbackToCurrent()).isFalse();
                });
        assertThat(lawSearchService.findArticles(lawId, art10, null))
                .singleElement().satisfies(a -> {
                    assertThat(a.content()).contains("미리 정하는");
                    assertThat(a.fallbackToCurrent()).isFalse();
                });
        // 2019 년: 이 법령의 어떤 행보다 앞섬 → 최신(2026)이 아니라 가장 가까운 2020 버전, 대체 표시
        assertThat(lawSearchService.findArticles(lawId, art10, LocalDate.of(2019, 1, 1)))
                .singleElement().satisfies(a -> {
                    assertThat(a.content()).contains("옛 조문");
                    assertThat(a.fallbackToCurrent()).isTrue();
                });
        // 2020 버전에 없던 제11조는 그 조문의 가장 옛 행(2026)으로 대체
        assertThat(lawSearchService.findArticles(lawId, art11, LocalDate.of(2019, 1, 1)))
                .singleElement().satisfies(a -> {
                    assertThat(a.content()).contains("통화로");
                    assertThat(a.fallbackToCurrent()).isTrue();
                });
        assertThat(lawSearchService.search(List.of("위약금"), List.of(lawId), LocalDate.of(2019, 1, 1), null))
                .singleElement().satisfies(a -> {
                    assertThat(a.content()).contains("옛 조문");
                    assertThat(a.fallbackToCurrent()).isTrue();
                });
    }

    @Test
    void 항이_없는_조문에_항_번호로_물으면_조문_전체를_준다() {
        saveOldAndNewVersions();
        LocalDate d = LocalDate.of(2026, 6, 1);

        assertThat(lawSearchService.findArticles(lawName, "제10조 제2항", d))
                .singleElement().satisfies(a -> {
                    assertThat(a.paragraphNo()).isNull();
                    assertThat(a.content()).contains("미리 정하는");
                });
        // 항이 있는 조문은 없는 항이면 빈 목록
        assertThat(lawSearchService.findArticles(lawName, "제10조의2 제9항", d)).isEmpty();
    }

    @Test
    void 키워드_검색은_조사가_붙은_낱말도_앞부분_일치로_찾고_점수순_상한을_지킨다() {
        Long lawId = saveOldAndNewVersions();
        LocalDate d = LocalDate.of(2026, 6, 1);

        List<LawArticleInfo> hits = lawSearchService.search(List.of("위약금", "교육비"), List.of(lawId), d, null);
        assertThat(hits).extracting(h -> h.articleNo() + "-" + h.articleBranchNo() + "-" + h.paragraphNo())
                // 제10조의2 ②항은 내용에 "교육비"가 없지만 조문 제목("교육비 반환")이 search_vector 에 들어 있어 걸린다
                .containsExactlyInAnyOrder("10-0-null", "10-2-1", "10-2-2");
        // 두 키워드가 모두 맞는 ①항(제목+내용 교육비)이 제목만 맞는 ②항보다 점수가 높다
        assertThat(hits.get(hits.size() - 1).paragraphNo()).isEqualTo(2);
        assertThat(hits).allMatch(h -> h.score() != null && h.score() > 0);
        assertThat(hits).noneMatch(h -> h.content().contains("옛 조문"));

        assertThat(lawSearchService.search(List.of("위약금", "교육비", "임금", "재직"), List.of(lawId), d, 2)).hasSize(2);
        assertThat(lawSearchService.search(List.of("!!!"), List.of(lawId), d, null)).isEmpty();
        assertThat(lawSearchService.search(List.of("없는낱말" + suffix), List.of(lawId), d, null)).isEmpty();
    }

    @Test
    void 숫자_규칙은_현행_근거_조문으로_넣고_다시_넣으면_갱신한다() {
        saveOldAndNewVersions();
        RuleSeed seed = new RuleSeed(ruleCode, LocalDate.of(2026, 1, 1), Map.of("hourly", 10320), lawName, 10, 2, 1, "테스트");

        Optional<Long> articleId = lawWriter.upsertRule(seed);
        Optional<Long> again = lawWriter.upsertRule(new RuleSeed(ruleCode, LocalDate.of(2026, 1, 1), Map.of("hourly", 10321),
                lawName, 10, 2, 1, "테스트2"));

        assertThat(articleId).isPresent().isEqualTo(again);
        Map<String, Object> row = jdbc.queryForMap("SELECT value_json::text AS v, law_article_id, note FROM rule_param WHERE rule_code = ?", ruleCode);
        assertThat(row.get("v").toString()).contains("10321");
        assertThat(row.get("law_article_id")).isEqualTo(articleId.get());
        assertThat(row.get("note")).isEqualTo("테스트2");
    }

    @Test
    void 문서별_관련_법령은_한_번만_넣고_없는_문서_종류는_FK_로_막힌다() {
        Long lawId = saveOldAndNewVersions();
        Integer types = jdbc.queryForObject("SELECT count(*) FROM document_type WHERE code = 'EMPLOYMENT'", Integer.class);
        Assumptions.assumeTrue(types != null && types == 1, "document_type EMPLOYMENT 행이 없으면 건너뜀");

        assertThat(lawWriter.insertDocumentTypeLaw("EMPLOYMENT", lawName, false)).isEqualTo(LawWriter.SeedResult.INSERTED);
        assertThat(lawWriter.insertDocumentTypeLaw("EMPLOYMENT", lawName, true)).isEqualTo(LawWriter.SeedResult.EXISTS);
        assertThat(lawWriter.insertDocumentTypeLaw("EMPLOYMENT", "없는법" + suffix, true)).isEqualTo(LawWriter.SeedResult.NO_LAW);
        assertThat(lawSearchService.findLawIds("EMPLOYMENT")).contains(lawId);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> lawWriter.insertDocumentTypeLaw("NO_TYPE_" + suffix, lawName, true))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_type_law WHERE law_id = ?", Integer.class, lawId)).isEqualTo(1);
    }

    /** 적재된 운영 데이터가 있을 때만: 근로기준법 제20조(위약 예정의 금지)를 키워드·별칭으로 찾는다. 읽기만 한다. */
    @Test
    void 적재된_근로기준법에서_위약금_검색() {
        Optional<Long> lawId = lawSearchService.resolveLawId("근기법");
        Assumptions.assumeTrue(lawId.isPresent(), "법령 적재 전이면 건너뜀");

        List<LawArticleInfo> hits = lawSearchService.search(List.of("위약금", "손해배상액"), List.of(lawId.get()), null, null);
        assertThat(hits).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(hits).anyMatch(h -> h.articleNo() == 20);
        assertThat(lawSearchService.findArticles("근로 기준법", "제20조", null))
                .singleElement().satisfies(a -> assertThat(a.title()).isEqualTo("위약 예정의 금지"));
    }
}
