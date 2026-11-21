package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.service.rule.BreakTimeRule.Outcome;
import com.team.docai.domain.analysis.service.rule.BreakTimeRule.Result;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 근로기준법 제54조 제1항: 4시간 → 30분, 8시간 → 1시간 이상 휴게 */
class BreakTimeRuleTest {

    static Map<String, Object> hours(String start, String end, Object... more) {
        Map<String, Object> m = new HashMap<>();
        m.put("hoursPerDay", null);
        m.put("daysPerWeek", 5);
        if (start != null) {
            m.put("workStart", start);
        }
        if (end != null) {
            m.put("workEnd", end);
        }
        for (int i = 0; i < more.length; i += 2) {
            m.put((String) more[i], more[i + 1]);
        }
        return m;
    }

    static List<Map<String, Object>> range(String s, String e) {
        return List.of(Map.of("start", s, "end", e));
    }

    @Test
    void 네_시간_근무에_휴게_없음이면_부족() {
        Result r = BreakTimeRule.check(hours("14:00", "18:00", "noBreak", true));

        assertThat(r.outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(r.comparison().leftValue()).isEqualTo("0분");
        assertThat(r.comparison().operator()).isEqualTo("<");
        assertThat(r.comparison().rightValue()).isEqualTo("30분");
        assertThat(r.comparison().rightLabel()).contains("4시간");
        assertThat(r.headline()).isEqualTo("하루 4시간 일하는데 휴게시간이 없어요");
        assertThat(r.whyCheck()).contains("14:00~18:00 근무로", "4시간이면 30분", "휴게시간이 없어요")
                .doesNotContain("위반", "불법", "무효");
    }

    @Test
    void 세_시간_59분은_휴게_의무가_없다() {
        assertThat(BreakTimeRule.check(hours("14:00", "17:59", "noBreak", true)).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void 여덟_시간에_휴게_30분이면_부족() {
        Result byHours = BreakTimeRule.check(hours(null, null, "hoursPerDay", 8, "breakMinutes", 30));
        assertThat(byHours.outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(byHours.comparison().rightValue()).isEqualTo("1시간");
        assertThat(byHours.headline()).isEqualTo("하루 8시간 일하는데 휴게시간이 1시간보다 짧아요");

        // 09:00~17:30 휴게 30분 → 근로 8시간
        Result byTime = BreakTimeRule.check(hours("09:00", "17:30", "breaks", range("12:00", "12:30")));
        assertThat(byTime.outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(byTime.comparison().leftValue()).isEqualTo("30분");
        assertThat(byTime.whyCheck()).contains("09:00~17:30 근무에서 휴게 30분을 빼면", "8시간이면 1시간");
    }

    @Test
    void 여덟_시간에_휴게_60분이면_통과() {
        assertThat(BreakTimeRule.check(hours(null, null, "hoursPerDay", 8, "breakMinutes", 60)).outcome())
                .isEqualTo(Outcome.PASS);
    }

    @Test
    void 아홉시부터_18시_휴게_1시간이면_통과() {
        assertThat(BreakTimeRule.check(hours("09:00", "18:00", "breaks", range("12:00", "13:00"))).outcome())
                .isEqualTo(Outcome.PASS);
    }

    @Test
    void 아홉시부터_18시_휴게_없음이면_1시간_필요() {
        Result r = BreakTimeRule.check(hours("09:00", "18:00", "noBreak", true));
        assertThat(r.outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(r.comparison().rightValue()).isEqualTo("1시간");
        assertThat(r.headline()).contains("9시간");
    }

    @Test
    void 열시부터_15시_휴게_30분이면_통과() {
        // 근로 4시간 30분 → 30분 필요
        assertThat(BreakTimeRule.check(hours("10:00", "15:00", "breaks", range("12:00", "12:30"))).outcome())
                .isEqualTo(Outcome.PASS);
    }

    @Test
    void 여러_휴게_구간은_더한다() {
        Map<String, Object> h = hours("09:00", "18:00", "breaks",
                List.of(Map.of("start", "12:00", "end", "12:30"), Map.of("start", "15:00", "end", "15:30")));
        assertThat(BreakTimeRule.check(h).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void 정보가_없으면_판정하지_않는다() {
        // 시각은 있으나 휴게에 대한 말이 없음
        assertThat(BreakTimeRule.check(hours("09:00", "18:00")).outcome()).isEqualTo(Outcome.SKIPPED);
        // 휴게 없음은 있으나 근로시간을 모름
        assertThat(BreakTimeRule.check(hours(null, null, "noBreak", true)).outcome()).isEqualTo(Outcome.SKIPPED);
        // 종업 시각만 있음, hoursPerDay 없음
        assertThat(BreakTimeRule.check(hours(null, "18:00", "noBreak", true)).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(BreakTimeRule.check(null).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(BreakTimeRule.check(Map.of()).outcome()).isEqualTo(Outcome.SKIPPED);
        // 기존 모양 {hoursPerDay, daysPerWeek} 만 있음
        assertThat(BreakTimeRule.check(Map.of("hoursPerDay", 8, "daysPerWeek", 5)).outcome())
                .isEqualTo(Outcome.SKIPPED);
        // 휴게 0분은 "적혀 있지 않음"과 구분되지 않아 모름으로 본다 (없음은 noBreak)
        assertThat(BreakTimeRule.check(hours("09:00", "18:00", "breakMinutes", 0)).outcome())
                .isEqualTo(Outcome.SKIPPED);
    }

    @Test
    void 휴게_없음과_휴게시간이_함께_있거나_시각이_이상하면_판정하지_않는다() {
        assertThat(BreakTimeRule.check(hours("09:00", "18:00", "noBreak", true, "breakMinutes", 60)).outcome())
                .isEqualTo(Outcome.SKIPPED);
        assertThat(BreakTimeRule.check(hours("09:00", "09:00", "noBreak", true)).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(BreakTimeRule.check(hours("9시", "18:00", "noBreak", true)).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(BreakTimeRule.check(hours("09:00", "10:00", "breakMinutes", 90)).outcome())
                .isEqualTo(Outcome.SKIPPED);
        assertThat(BreakTimeRule.check(hours("09:00", "18:00", "breaks", List.of(Map.of("start", "12:00"))))
                .outcome()).isEqualTo(Outcome.SKIPPED);
    }

    @Test
    void 시각과_hoursPerDay가_30분_넘게_다르면_판정하지_않는다() {
        // 09:00~22:00 휴게 없음 = 13시간인데 hoursPerDay 4 → 판정 안 함
        Result mismatch = BreakTimeRule.check(hours("09:00", "22:00", "noBreak", true, "hoursPerDay", 4));
        assertThat(mismatch.outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(mismatch.skipReason()).contains("근로시간이 서로 맞지 않음");
        // 09:00~18:00 휴게 60분, hoursPerDay 8 → 맞음, 정상 판정 (통과)
        assertThat(BreakTimeRule.check(hours("09:00", "18:00", "breakMinutes", 60, "hoursPerDay", 8)).outcome())
                .isEqualTo(Outcome.PASS);
        // 09:00~18:00 휴게 없음, hoursPerDay 9 → 맞음, 부족
        assertThat(BreakTimeRule.check(hours("09:00", "18:00", "noBreak", true, "hoursPerDay", 9)).outcome())
                .isEqualTo(Outcome.SHORTFALL);
        // 경계: 차이 정확히 30분은 판정, 31분은 안 함 (14:00~18:00 = 240분)
        assertThat(BreakTimeRule.check(hours("14:00", "18:00", "noBreak", true, "hoursPerDay", 4.5)).outcome())
                .isEqualTo(Outcome.SHORTFALL);
        assertThat(BreakTimeRule.check(hours("14:00", "18:31", "noBreak", true, "hoursPerDay", 4)).outcome())
                .isEqualTo(Outcome.SKIPPED);
    }

    @Test
    void 자정을_넘는_근무는_종업에_24시간을_더한다() {
        // 22:00~06:00 휴게 없음 → 8시간
        Result r = BreakTimeRule.check(hours("22:00", "06:00", "noBreak", true));
        assertThat(r.outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(r.comparison().rightValue()).isEqualTo("1시간");
        // 22:00~07:00, 휴게 23:30~00:30 → 8시간, 1시간 휴게
        assertThat(BreakTimeRule.check(hours("22:00", "07:00", "breaks", range("23:30", "00:30"))).outcome())
                .isEqualTo(Outcome.PASS);
    }

    @Test
    void 경계는_정확히_4시간과_8시간을_포함한다() {
        assertThat(BreakTimeRule.required(239)).isZero();
        assertThat(BreakTimeRule.required(240)).isEqualTo(30);
        assertThat(BreakTimeRule.required(479)).isEqualTo(30);
        assertThat(BreakTimeRule.required(480)).isEqualTo(60);
        // 4시간 휴게 정확히 30분 → 통과, 29분 → 부족
        assertThat(BreakTimeRule.check(hours(null, null, "hoursPerDay", 4, "breakMinutes", 30)).outcome())
                .isEqualTo(Outcome.PASS);
        assertThat(BreakTimeRule.check(hours(null, null, "hoursPerDay", 4, "breakMinutes", 29)).outcome())
                .isEqualTo(Outcome.SHORTFALL);
        assertThat(BreakTimeRule.check(hours(null, null, "hoursPerDay", 3.5, "noBreak", true)).outcome())
                .isEqualTo(Outcome.PASS);
    }

    @Test
    void 시간_표시() {
        assertThat(BreakTimeRule.duration(0)).isEqualTo("0분");
        assertThat(BreakTimeRule.duration(30)).isEqualTo("30분");
        assertThat(BreakTimeRule.duration(60)).isEqualTo("1시간");
        assertThat(BreakTimeRule.duration(270)).isEqualTo("4시간 30분");
        assertThat(BreakTimeRule.minutes("24:00")).contains(1440);
        assertThat(BreakTimeRule.minutes("24:30")).isEmpty();
        assertThat(BreakTimeRule.minutes("9:05")).contains(545);
    }
}
