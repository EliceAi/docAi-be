package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.entity.RefFoundBy;
import com.team.docai.domain.analysis.service.rule.EvidenceCollector.ExistingRef;
import com.team.docai.domain.analysis.service.rule.EvidenceCollector.RefDraft;
import com.team.docai.domain.law.dto.GuidanceInfo;
import com.team.docai.domain.law.dto.LawArticleInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCollectorTest {

    static final LocalDate BASE = LocalDate.of(2026, 11, 20);

    static LawArticleInfo article(long id, int no, Integer paragraph) {
        return article(id, no, paragraph, LocalDate.of(2026, 10, 8), false);
    }

    static LawArticleInfo article(long id, int no, Integer paragraph, LocalDate from, boolean fallback) {
        return new LawArticleInfo(id, 1L, "근로기준법", no, 0, paragraph, "제목" + no, "내용" + id, from, null, null, fallback);
    }

    static GuidanceInfo guidance(long id) {
        return new GuidanceInfo(id, "PENALTY_ON_RESIGNATION", "교육비 반환 해석 메모", "메모 내용", "판례");
    }

    @Test
    void 꼬리표_힌트_검색_순서로_채우고_중복을_빼고_L번호를_매긴다() {
        EvidenceCollector c = new EvidenceCollector(7L, List.of(), List.of(), BASE, BASE, 5);
        c.addLaws(RefFoundBy.TAG_MAP, List.of(article(20, 20, null)));
        c.addLaws(RefFoundBy.AI_HINT, List.of(article(20, 20, null), article(21, 21, null)));
        assertThat(c.wantsMoreLaws()).isTrue();
        c.addLaws(RefFoundBy.SEARCH, List.of(article(21, 21, null), article(30, 30, 1)));

        List<RefDraft> drafts = c.drafts();

        assertThat(drafts).extracting(RefDraft::refLabel).containsExactly("L1", "L2", "L3");
        assertThat(drafts).extracting(RefDraft::foundBy)
                .containsExactly(RefFoundBy.TAG_MAP, RefFoundBy.AI_HINT, RefFoundBy.SEARCH);
        assertThat(drafts).extracting(RefDraft::lawArticleId).containsExactly(20L, 21L, 30L);
        assertThat(drafts).allMatch(d -> !d.cited() && d.clauseId() == 7L && d.guidanceId() == null);
        assertThat(drafts.get(0).refTitle()).isEqualTo("근로기준법 제20조 (제목20)");
        assertThat(drafts.get(2).refTitle()).isEqualTo("근로기준법 제30조제1항 (제목30)");
        assertThat(drafts.get(0).refText()).isEqualTo("내용20");
    }

    @Test
    void 조항당_5개까지_차면_더_넣지_않는다() {
        EvidenceCollector c = new EvidenceCollector(7L, List.of(), List.of(), BASE, BASE, 5);
        c.addLaws(RefFoundBy.TAG_MAP, List.of(article(1, 6, 1), article(2, 6, 2), article(3, 6, 3)));
        c.addLaws(RefFoundBy.AI_HINT, List.of(article(4, 7, null), article(5, 8, null), article(6, 9, null)));
        assertThat(c.wantsMoreLaws()).isFalse();
        c.addLaws(RefFoundBy.SEARCH, List.of(article(7, 10, null)));
        assertThat(c.drafts()).hasSize(5).extracting(RefDraft::lawArticleId).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void 이미_붙은_RULE_근거를_세고_같은_조문은_빼고_다음_번호부터() {
        List<ExistingRef> existing = List.of(new ExistingRef("L1", 405L, null));
        EvidenceCollector c = new EvidenceCollector(7L, existing, List.of(), BASE, BASE, 5);
        c.addLaws(RefFoundBy.TAG_MAP, List.of(article(405, 6, 1), article(406, 6, 2), article(407, 6, 3),
                article(408, 6, 4), article(409, 6, 5), article(410, 6, 6)));
        List<RefDraft> drafts = c.drafts();
        assertThat(drafts).extracting(RefDraft::refLabel).containsExactly("L2", "L3", "L4", "L5");
        assertThat(drafts).extracting(RefDraft::lawArticleId).doesNotContain(405L);
    }

    @Test
    void 해석_메모는_1칸을_남겨_두고_조문_뒤에_G번호로() {
        EvidenceCollector c = new EvidenceCollector(7L, List.of(), List.of(guidance(3), guidance(4)), BASE, BASE, 5);
        c.addLaws(RefFoundBy.TAG_MAP, List.of(article(1, 1, null), article(2, 2, null), article(3, 3, null),
                article(4, 4, null), article(5, 5, null)));
        List<RefDraft> drafts = c.drafts();
        assertThat(drafts).extracting(RefDraft::refLabel).containsExactly("L1", "L2", "L3", "L4", "G5");
        RefDraft g = drafts.get(4);
        assertThat(g.guidanceId()).isEqualTo(3L);
        assertThat(g.lawArticleId()).isNull();
        assertThat(g.refTitle()).isEqualTo("교육비 반환 해석 메모");
        assertThat(g.refText()).isEqualTo("메모 내용");
    }

    @Test
    void 이미_꽉_찬_조항에는_넣지_않는다() {
        List<ExistingRef> existing = List.of(new ExistingRef("L1", 1L, null), new ExistingRef("L2", 2L, null),
                new ExistingRef("L3", 3L, null), new ExistingRef("L4", 4L, null), new ExistingRef("G5", null, 9L));
        EvidenceCollector c = new EvidenceCollector(7L, existing, List.of(guidance(3)), BASE, BASE, 5);
        assertThat(c.wantsMoreLaws()).isFalse();
        c.addLaws(RefFoundBy.TAG_MAP, List.of(article(10, 10, null)));
        assertThat(c.drafts()).isEmpty();
        assertThat(EvidenceCollector.nextNumber(existing)).isEqualTo(6);
    }

    @Test
    void 기준일과_오늘보다_뒤에_시행되는_시행_예정_조문은_시행일을_넣은_시행_예정_표시() {
        LocalDate today = LocalDate.of(2026, 11, 22);
        LawArticleInfo upcoming = article(1, 34, null, LocalDate.of(2027, 4, 28), true);
        RefDraft r = EvidenceCollector.lawRef(7L, "L1", RefFoundBy.SEARCH, false, upcoming, BASE, today);
        assertThat(r.refText()).isEqualTo("[시행 예정 조문(2027-04-28 시행)으로 대신함: 지금 적용되는 조문과 다를 수 있음]\n내용1");

        // 기준일보다는 뒤지만 오늘은 시행 중인 조문은 기존 현행 대체 표시
        LawArticleInfo current = article(2, 20, null, LocalDate.of(2026, 10, 8), true);
        assertThat(EvidenceCollector.lawRef(7L, "L1", RefFoundBy.SEARCH, false, current, LocalDate.of(2025, 3, 1), today).refText())
                .isEqualTo(EvidenceCollector.FALLBACK_NOTE + "내용2");

        // 기준일 < 시행일 = 분석일: 분석일에 이미 시행 중이므로 현행 대체 표시
        LawArticleInfo effectiveToday = article(4, 34, null, today, true);
        assertThat(EvidenceCollector.lawRef(7L, "L1", RefFoundBy.SEARCH, false, effectiveToday, BASE, today).refText())
                .isEqualTo(EvidenceCollector.FALLBACK_NOTE + "내용4");

        // 기준일이 없어도 fallbackToCurrent 면 표시한다: 분석일 기준으로 시행 예정·현행을 가른다
        assertThat(EvidenceCollector.lawRef(7L, "L1", RefFoundBy.SEARCH, false, upcoming, null, today).refText())
                .isEqualTo("[시행 예정 조문(2027-04-28 시행)으로 대신함: 지금 적용되는 조문과 다를 수 있음]\n내용1");
        assertThat(EvidenceCollector.lawRef(7L, "L1", RefFoundBy.SEARCH, false, current, null, today).refText())
                .isEqualTo(EvidenceCollector.FALLBACK_NOTE + "내용2");
        // 기준일이 없고 fallbackToCurrent 도 아니면 표시하지 않는다
        assertThat(EvidenceCollector.lawRef(7L, "L1", RefFoundBy.SEARCH, false,
                article(5, 34, null, LocalDate.of(2027, 4, 28), false), null, today).refText()).isEqualTo("내용5");

        // 계약일이 시행일 뒤면(앞으로 시작하는 계약) 대신한 행이 아니므로 표시하지 않는다
        LawArticleInfo effectiveOnBase = article(3, 34, null, LocalDate.of(2027, 4, 28), false);
        assertThat(EvidenceCollector.lawRef(7L, "L1", RefFoundBy.SEARCH, false, effectiveOnBase, LocalDate.of(2027, 5, 1), today)
                .refText()).isEqualTo("내용3");
    }

    @Test
    void 기준일보다_뒤에_시행된_현행_조문은_ref_text_앞에_대체_표시() {
        LawArticleInfo later = article(1, 20, null, LocalDate.of(2026, 10, 8), false);
        RefDraft r = EvidenceCollector.lawRef(7L, "L1", RefFoundBy.TAG_MAP, false, later, LocalDate.of(2025, 3, 1), BASE);
        assertThat(r.refText()).isEqualTo(EvidenceCollector.FALLBACK_NOTE + "내용1");

        LawArticleInfo flagged = article(2, 20, null, LocalDate.of(2020, 1, 1), true);
        assertThat(EvidenceCollector.lawRef(7L, "L1", RefFoundBy.AI_HINT, false, flagged, BASE, BASE).refText())
                .startsWith(EvidenceCollector.FALLBACK_NOTE);
        assertThat(EvidenceCollector.lawRef(7L, "L1", RefFoundBy.AI_HINT, false, article(3, 20, null), BASE, BASE).refText())
                .isEqualTo("내용3");
    }

    @Test
    void RULE_근거는_cited_true() {
        RefDraft r = EvidenceCollector.lawRef(7L, "L1", RefFoundBy.RULE, true, article(405, 6, 1), BASE, BASE);
        assertThat(r.cited()).isTrue();
        assertThat(r.foundBy()).isEqualTo(RefFoundBy.RULE);
    }

    @Test
    void 꼬리표_조문은_처음_나온_조문_순서를_지키고_조문_안에서는_항_순서() {
        List<LawArticleInfo> raw = List.of(article(9, 6, 9), article(1, 6, 1), article(52, 5, 2),
                article(2, 6, 2), article(51, 5, 1));
        assertThat(EvidenceCollector.orderTagArticles(raw)).extracting(LawArticleInfo::lawArticleId)
                .containsExactly(1L, 2L, 9L, 51L, 52L);
    }

    @Test
    void 긴_제목은_150자로_자른다() {
        LawArticleInfo longTitle = new LawArticleInfo(1L, 1L, "근로기준법", 1, 0, null, "가".repeat(200), "c",
                LocalDate.of(2020, 1, 1), null, null, false);
        assertThat(EvidenceCollector.lawRef(7L, "L1", RefFoundBy.SEARCH, false, longTitle, BASE, BASE).refTitle())
                .hasSize(150);
    }

    @Test
    void 꼬리표_조문_중_기준일_전에_효력이_끝난_행은_뺀다() {
        LawArticleInfo ended = new LawArticleInfo(1L, 1L, "근로기준법", 20, 0, null, "t", "옛 내용",
                LocalDate.of(2010, 1, 1), LocalDate.of(2020, 12, 31), null, false);
        LawArticleInfo endsOnBase = new LawArticleInfo(2L, 1L, "근로기준법", 20, 0, 1, "t", "c",
                LocalDate.of(2021, 1, 1), BASE, null, false);
        LawArticleInfo current = article(3, 20, 2);
        assertThat(EvidenceCollector.notEndedBefore(List.of(ended, endsOnBase, current), BASE))
                .extracting(LawArticleInfo::lawArticleId).containsExactly(2L, 3L);
        assertThat(EvidenceCollector.notEndedBefore(List.of(ended), null)).hasSize(1);
    }
}
