package com.team.docai.domain.law.service;

import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.entity.Law;
import com.team.docai.domain.law.entity.LawArticle;
import com.team.docai.domain.law.repository.DocumentTypeLawRepository;
import com.team.docai.domain.law.repository.GuidanceRepository;
import com.team.docai.domain.law.repository.IssueTagArticleRepository;
import com.team.docai.domain.law.repository.IssueTagRepository;
import com.team.docai.domain.law.repository.LawAliasRepository;
import com.team.docai.domain.law.repository.LawArticleRepository;
import com.team.docai.domain.law.repository.LawRepository;
import com.team.docai.global.config.LawProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 일상용어로 넓힌 검색(searchWithDailyTerms)의 순서·상한. 저장소는 모의 객체 (검색 결과 ID 를 정해 둠). */
class LawSearchServiceDailyTermTest {

    static final Long LAW_ID = 10L;

    LawArticleRepository articles;
    LawRepository laws;
    /** 원래 검색어(알바만)로 찾는 조문 ID, 법령용어가 들어간 검색으로 찾는 조문 ID (점수 순) */
    List<Long> plainHits;
    List<Long> widenedHits;

    @BeforeEach
    void setUp() {
        articles = mock(LawArticleRepository.class);
        laws = mock(LawRepository.class);
        plainHits = List.of(1L);
        widenedHits = List.of(3L, 1L, 4L, 5L, 6L, 7L);
        when(articles.searchIds(anyString(), anyBoolean(), any(), any(), anyDouble(), anyInt())).thenAnswer(inv -> {
            String tsquery = inv.getArgument(0);
            int limit = inv.getArgument(5);
            List<Long> ids = tsquery.contains("단시간근로") ? widenedHits : plainHits;
            List<Object[]> rows = new ArrayList<>();
            double score = 1.0;
            for (Long id : ids.subList(0, Math.min(limit, ids.size()))) {
                rows.add(new Object[]{id, score});
                score -= 0.1;
            }
            return rows;
        });
        when(articles.findAllById(any())).thenAnswer(inv -> {
            List<LawArticle> out = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                LawArticle a = LawArticle.create(LAW_ID, ((Long) id).intValue(), 0, null, "제목", "내용 " + id, "M1",
                        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 1));
                ReflectionTestUtils.setField(a, "lawArticleId", id);
                out.add(a);
            }
            return out;
        });
        Law law = Law.create("근로기준법", "법률", "001872", OffsetDateTime.now());
        ReflectionTestUtils.setField(law, "lawId", LAW_ID);
        when(laws.findAllById(any())).thenReturn(List.of(law));
    }

    LawSearchService service(int maxKeywords) {
        LawProperties props = new LawProperties(false, false, false, false, "http://localhost", Duration.ZERO, Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 3, 3, "", 5, 20, 0.0, maxKeywords, 500);
        LawTermDictionary dictionary = new LawTermDictionary(Map.of("알바", List.of("단시간근로")));
        return new LawSearchService(laws, mock(LawAliasRepository.class), articles, mock(DocumentTypeLawRepository.class),
                mock(IssueTagArticleRepository.class), mock(GuidanceRepository.class), mock(IssueTagRepository.class),
                props, dictionary);
    }

    static List<Long> ids(List<LawArticleInfo> infos) {
        return infos.stream().map(LawArticleInfo::lawArticleId).toList();
    }

    @Test
    void 원래_검색으로_칸이_차면_넓히지_않는다() {
        plainHits = List.of(1L, 2L, 8L, 9L, 11L);

        List<LawArticleInfo> out = service(10).searchWithDailyTerms(List.of("알바"), List.of(LAW_ID), null, 5);

        assertThat(ids(out)).containsExactly(1L, 2L, 8L, 9L, 11L);
        verify(articles, times(1)).searchIds(anyString(), anyBoolean(), any(), any(), anyDouble(), anyInt());
    }

    @Test
    void 원래_결과를_앞에_그대로_두고_새_조문만_점수_순으로_채운다() {
        List<LawArticleInfo> out = service(10).searchWithDailyTerms(List.of("알바"), List.of(LAW_ID), null, 5);

        assertThat(ids(out)).containsExactly(1L, 3L, 4L, 5L, 6L);
    }

    @Test
    void 상한은_그대로다() {
        assertThat(ids(service(10).searchWithDailyTerms(List.of("알바"), List.of(LAW_ID), null, 2))).containsExactly(1L, 3L);
        assertThat(ids(service(10).searchWithDailyTerms(List.of("알바"), List.of(LAW_ID), null, 99))).hasSize(6);
    }

    @Test
    void 사전에_맞는_말이_없으면_두_번째_검색을_하지_않는다() {
        List<LawArticleInfo> out = service(10).searchWithDailyTerms(List.of("위약금"), List.of(LAW_ID), null, 5);

        assertThat(ids(out)).containsExactly(1L);
        verify(articles, times(1)).searchIds(anyString(), anyBoolean(), any(), any(), anyDouble(), anyInt());
    }

    @Test
    void 두_번째_검색은_법령용어를_앞에_둬_검색어_수_상한에_잘리지_않는다() {
        service(2).searchWithDailyTerms(List.of("수습", "기간", "알바"), List.of(LAW_ID), null, 5);

        ArgumentCaptor<String> tsquery = ArgumentCaptor.forClass(String.class);
        verify(articles, times(2)).searchIds(tsquery.capture(), anyBoolean(), any(), any(), anyDouble(), anyInt());
        assertThat(tsquery.getAllValues().get(0)).isEqualTo("수습:* | 기간:*");
        assertThat(tsquery.getAllValues().get(1)).isEqualTo("단시간근로:* | 수습:*");
    }
}
