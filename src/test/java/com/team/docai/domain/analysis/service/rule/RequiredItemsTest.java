package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.service.rule.RequiredItems.Item;
import com.team.docai.domain.analysis.service.rule.RequiredItems.KeyTermFact;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredItemsTest {

    /** 테스트_정답세트/01_근로_정상 의 주요 조건 라벨과 상태. */
    static List<KeyTermFact> answer01() {
        List<KeyTermFact> list = new ArrayList<>();
        for (String label : List.of("계약기간", "근무장소", "업무", "근로시간", "휴일", "임금", "임금 지급일", "연차휴가")) {
            list.add(new KeyTermFact(label, KeyTermState.OK));
        }
        return list;
    }

    @Test
    void 정답세트_01은_빠진_항목이_없다() {
        assertThat(RequiredItems.findMissing("EMPLOYMENT", answer01())).isEmpty();
        assertThat(RequiredItems.unmatched("EMPLOYMENT", answer01())).isEmpty();
    }

    @Test
    void MISSING_인_필수_항목만_빠짐으로_본다() {
        List<KeyTermFact> facts = new ArrayList<>(answer01());
        facts.set(4, new KeyTermFact("휴일", KeyTermState.MISSING));
        facts.set(7, new KeyTermFact("연차휴가", KeyTermState.MISSING));
        facts.set(0, new KeyTermFact("계약기간", KeyTermState.MISSING)); // 필수 목록에 없음

        List<Item> missing = RequiredItems.findMissing("EMPLOYMENT", facts);

        assertThat(missing).extracting(Item::name).containsExactly("휴일", "연차휴가");
        assertThat(missing.get(0).description()).isEqualTo("주휴일을 꼭 적어야 해요");
        assertThat(missing.get(0).lawName()).isEqualTo("근로기준법");
        assertThat(missing.get(0).article()).isEqualTo("제17조제1항");
    }

    @Test
    void 가렸거나_못_읽은_항목은_누락이_아니다() {
        List<KeyTermFact> facts = new ArrayList<>(answer01());
        facts.set(4, new KeyTermFact("휴일", KeyTermState.MASKED));
        facts.set(5, new KeyTermFact("임금", KeyTermState.UNREADABLE));
        assertThat(RequiredItems.findMissing("EMPLOYMENT", facts)).isEmpty();
    }

    @Test
    void 같은_항목의_다른_라벨에_값이_있으면_누락이_아니고_공백은_무시한다() {
        List<KeyTermFact> facts = new ArrayList<>(answer01());
        facts.set(4, new KeyTermFact("휴일", KeyTermState.MISSING));
        facts.add(new KeyTermFact("주 휴일", KeyTermState.OK));
        facts.set(1, new KeyTermFact("근무 장소", KeyTermState.MISSING));
        assertThat(RequiredItems.findMissing("EMPLOYMENT", facts)).extracting(Item::name).containsExactly("근무장소");
    }

    @Test
    void 주요_조건_행이_없으면_판단하지_않고_따로_알려_준다() {
        List<KeyTermFact> facts = List.of(new KeyTermFact("임금", KeyTermState.OK));
        assertThat(RequiredItems.findMissing("EMPLOYMENT", facts)).isEmpty();
        assertThat(RequiredItems.unmatched("EMPLOYMENT", facts))
                .containsExactly("근로시간", "휴일", "연차휴가", "근무장소", "업무");
    }

    @Test
    void 필수_목록이_없는_문서_종류는_빈_결과() {
        assertThat(RequiredItems.findMissing("LEASE", List.of(new KeyTermFact("보증금", KeyTermState.MISSING)))).isEmpty();
    }
}
