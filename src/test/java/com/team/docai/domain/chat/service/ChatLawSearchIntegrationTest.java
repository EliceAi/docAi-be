package com.team.docai.domain.chat.service;

import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.service.LawSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 챗봇 일반 질문(연결된 분석 없음 → 모든 법령)의 조문 검색을 실제 DB 로 확인한다 (ChatContextReader 와 같은 검색어·상한). 읽기만 한다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class ChatLawSearchIntegrationTest {

    @Autowired LawSearchService lawSearchService;

    List<String> labels(String question) {
        return lawSearchService.searchWithDailyTerms(ChatKeywords.extract(question), List.of(), null,
                        ChatContextReader.MAX_SEARCH_SOURCES).stream()
                .map((LawArticleInfo a) -> a.lawName() + " " + a.articleNo() + "-" + (a.paragraphNo() == null ? 0 : a.paragraphNo()))
                .toList();
    }

    @Test
    void 수습_기간_최저임금_질문이_수습_감액_조문을_찾는다() {
        assertThat(labels("수습 기간에는 최저임금보다 적게 줘도 되나요?"))
                .containsAnyOf("최저임금법 시행령 3-0", "최저임금법 5-2");
    }

    @Test
    void 알바_퇴직금_질문이_일상용어를_넓혀_퇴직급여_조문을_찾는다() {
        assertThat(ChatKeywords.extract("알바도 퇴직금 받을 수 있나요?")).contains("알바", "퇴직금");
        List<String> hits = labels("알바도 퇴직금 받을 수 있나요?");
        assertThat(hits).hasSize(ChatContextReader.MAX_SEARCH_SOURCES);
        assertThat(hits).anyMatch(h -> h.startsWith("근로자퇴직급여 보장법"));
    }
}
