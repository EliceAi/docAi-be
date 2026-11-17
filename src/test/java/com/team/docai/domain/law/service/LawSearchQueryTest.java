package com.team.docai.domain.law.service;

import com.team.docai.domain.law.dto.ArticleNumber;
import com.team.docai.domain.law.entity.LawNames;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LawSearchQueryTest {

    @Test
    void 키워드를_앞부분_일치_tsquery_로_바꾸고_특수문자는_버린다() {
        assertThat(LawSearchService.toTsQuery(List.of("위약금", "교육 비용", "a&b|c:*!", "  "), 10, 500))
                .isEqualTo("위약금:* | (교육:* & 비용:*) | (a:* & b:* & c:*)");
        assertThat(LawSearchService.toTsQuery(Arrays.asList(null, "''"), 10, 500)).isEmpty();
        assertThat(LawSearchService.toTsQuery(null, 10, 500)).isEmpty();
    }

    @Test
    void 키워드_수와_tsquery_길이에_상한을_둔다() {
        assertThat(LawSearchService.toTsQuery(List.of("가", "나", "다", "라"), 2, 500)).isEqualTo("가:* | 나:*");
        // "가나다:*"(5) + " | 라마바:*"(8) = 13 > 12 이므로 두 번째부터 버림
        assertThat(LawSearchService.toTsQuery(List.of("가나다", "라마바", "사"), 10, 12)).isEqualTo("가나다:*");
        // 같은 키워드는 한 번만 세고 상한에 넣지 않는다
        assertThat(LawSearchService.toTsQuery(List.of("가", "가", "나"), 2, 500)).isEqualTo("가:* | 나:*");
    }

    @Test
    void 조문_번호_글자를_조_가지_항으로() {
        assertThat(ArticleNumber.parse("제10조의2")).contains(new ArticleNumber(10, 2, null));
        assertThat(ArticleNumber.parse("제17조 제1항")).contains(new ArticleNumber(17, 0, 1));
        assertThat(ArticleNumber.parse("제3조의2제1항")).contains(new ArticleNumber(3, 2, 1));
        assertThat(ArticleNumber.parse("20조")).contains(new ArticleNumber(20, 0, null));
        assertThat(ArticleNumber.parse("20")).contains(new ArticleNumber(20, 0, null));
        assertThat(ArticleNumber.parse("10-2")).contains(new ArticleNumber(10, 2, null));
        assertThat(ArticleNumber.parse("제17조제1항제1호")).contains(new ArticleNumber(17, 0, 1));
        assertThat(ArticleNumber.parse("제20조(위약 예정의 금지)")).contains(new ArticleNumber(20, 0, null));
        assertThat(ArticleNumber.parse("제2장")).isEmpty();
        assertThat(ArticleNumber.parse("제3항")).isEmpty();
        assertThat(ArticleNumber.parse("2024년")).isEmpty();
        assertThat(ArticleNumber.parse("10의2")).isEmpty();
        assertThat(ArticleNumber.parse("제10조 위반")).isEmpty();
        assertThat(ArticleNumber.parse("근로기준법")).isEmpty();
        assertThat(ArticleNumber.parse(null)).isEmpty();
        assertThat(new ArticleNumber(10, 2, null).label()).isEqualTo("제10조의2");
        assertThat(new ArticleNumber(17, 0, 1).label()).isEqualTo("제17조제1항");
    }

    @Test
    void 법령_이름은_띄어쓰기_가운뎃점_괄호를_빼고_비교한다() {
        assertThat(LawNames.normalize("근로자퇴직급여 보장법")).isEqualTo("근로자퇴직급여보장법");
        assertThat(LawNames.normalize("근로 기준법")).isEqualTo(LawNames.normalize("근로기준법"));
        assertThat(LawNames.normalize("기간제 및 단시간근로자 보호 등에 관한 법률(기간제법)ㆍ"))
                .isEqualTo("기간제및단시간근로자보호등에관한법률기간제법");
    }
}
