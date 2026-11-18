package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.entity.NumberComparison;
import com.team.docai.domain.analysis.service.rule.MinimumWageRule.Outcome;
import com.team.docai.domain.analysis.service.rule.MinimumWageRule.Result;
import com.team.docai.domain.analysis.service.rule.MinimumWageRule.Standard;
import com.team.docai.domain.analysis.service.rule.MinimumWageRule.WageType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MinimumWageRuleTest {

    static final Standard MIN_2026 = new Standard(BigDecimal.valueOf(10320), LocalDate.of(2026, 1, 1), 405L);

    static Map<String, Object> wage(Object type, Object amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("amount", amount);
        return m;
    }

    static Map<String, Object> hours(Object perDay, Object daysPerWeek) {
        Map<String, Object> m = new HashMap<>();
        m.put("hoursPerDay", perDay);
        m.put("daysPerWeek", daysPerWeek);
        return m;
    }

    @Test
    void 시급이_최저임금과_같으면_통과() {
        assertThat(MinimumWageRule.check(wage("HOURLY", 10320), null, MIN_2026).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void 시급이_1원이라도_낮으면_미달이고_비교와_문구를_만든다() {
        Result r = MinimumWageRule.check(wage("HOURLY", 10000), hours(4, 5), MIN_2026);

        assertThat(r.outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(r.comparison()).isEqualTo(
                new NumberComparison("계약서 시급", "10,000원", "<", "2026년 최저임금", "10,320원"));
        assertThat(r.headline()).isEqualTo("시급이 2026년 최저임금보다 낮아요");
        // 320원 × 주 20시간 × 365/7/12 = 27,809 → 100원 단위 버림 (명세 예시 "한 달 약 27,800원")
        assertThat(r.whyCheck()).contains("시간당 320원", "주 20시간이면 한 달 약 27,800원");
        assertThat(MinimumWageRule.check(wage("HOURLY", 10319), null, MIN_2026).outcome()).isEqualTo(Outcome.SHORTFALL);
    }

    @Test
    void 월급은_근로시간을_모르면_209시간으로_환산한다_경계() {
        // 10,320 × 209 = 2,156,880
        assertThat(MinimumWageRule.check(wage("MONTHLY", 2156880), null, MIN_2026).outcome()).isEqualTo(Outcome.PASS);
        Result r = MinimumWageRule.check(wage("MONTHLY", 2156879), null, MIN_2026);
        assertThat(r.outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(r.comparison().rightValue()).isEqualTo("2,156,880원");
        assertThat(r.comparison().rightLabel()).contains("월 209시간");
        assertThat(r.headline()).isEqualTo("월급이 2026년 최저임금보다 낮아요");
        assertThat(r.whyCheck()).contains("한 달 약 1원 적어요");
    }

    @Test
    void 주40시간이면_209시간_주20시간이면_주휴포함_104시간_주15시간미만은_주휴없음() {
        assertThat(MinimumWageRule.monthlyHours(BigDecimal.valueOf(40))).isEqualTo(209);
        assertThat(MinimumWageRule.monthlyHours(BigDecimal.valueOf(52))).isEqualTo(209);
        assertThat(MinimumWageRule.monthlyHours(BigDecimal.valueOf(20))).isEqualTo(104);
        assertThat(MinimumWageRule.monthlyHours(BigDecimal.valueOf(14))).isEqualTo(61);
        assertThat(MinimumWageRule.monthlyHours(null)).isEqualTo(209);
    }

    @Test
    void 단시간_월급은_주_소정근로시간으로_환산해_오탐하지_않는다() {
        // 주 20시간: 104시간 × 10,320 = 1,073,280. 209시간으로 나누면 미달로 잘못 나온다
        assertThat(MinimumWageRule.check(wage("MONTHLY", 1100000), hours(4, 5), MIN_2026).outcome())
                .isEqualTo(Outcome.PASS);
        assertThat(MinimumWageRule.check(wage("MONTHLY", 1000000), hours(4, 5), MIN_2026).outcome())
                .isEqualTo(Outcome.SHORTFALL);
    }

    @Test
    void 정답세트_01_월250만원_주40시간은_통과() {
        Result r = MinimumWageRule.check(wage("MONTHLY", 2500000), hours(8, 5), MIN_2026);
        assertThat(r.outcome()).isEqualTo(Outcome.PASS);
        assertThat(r.comparison()).isNull();
    }

    @Test
    void value_json_모양이_달라도_읽는다() {
        assertThat(MinimumWageRule.check(wage("monthly", "2,000,000"), null, MIN_2026).outcome())
                .isEqualTo(Outcome.SHORTFALL);
        assertThat(MinimumWageRule.check(wage("시급", "10,000원"), null, MIN_2026).outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(MinimumWageRule.check(wage("HOURLY", 10320.0), null, MIN_2026).outcome()).isEqualTo(Outcome.PASS);
        Map<String, Object> withUnit = wage("HOURLY", 9000L);
        withUnit.put("unit", "KRW");
        assertThat(MinimumWageRule.check(withUnit, null, MIN_2026).outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(MinimumWageRule.check(wage("HOURLY", 9000), Map.of("hoursPerWeek", 40), MIN_2026).whyCheck())
                .contains("주 40시간이면");
    }

    @Test
    void 계산할_수_없으면_건너뛴다() {
        assertThat(MinimumWageRule.check(null, null, MIN_2026).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(MinimumWageRule.check(wage("WEEKLY", 400000), null, MIN_2026).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(MinimumWageRule.check(wage(null, 10000), null, MIN_2026).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(MinimumWageRule.check(wage("HOURLY", null), null, MIN_2026).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(MinimumWageRule.check(wage("HOURLY", "협의"), null, MIN_2026).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(MinimumWageRule.check(wage("HOURLY", 0), null, MIN_2026).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(MinimumWageRule.check(wage("DAILY", 80000), null, MIN_2026).outcome()).isEqualTo(Outcome.SKIPPED);
    }

    @Test
    void 일급과_연봉() {
        // 일급: 10,320 × 8 = 82,560
        assertThat(MinimumWageRule.check(wage("DAILY", 82560), hours(8, 5), MIN_2026).outcome()).isEqualTo(Outcome.PASS);
        Result daily = MinimumWageRule.check(wage("DAILY", 80000), hours(8, 5), MIN_2026);
        assertThat(daily.outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(daily.type()).isEqualTo(WageType.DAILY);
        // 연봉: 2,156,880 × 12 = 25,882,560
        assertThat(MinimumWageRule.check(wage("ANNUAL", 25882560), null, MIN_2026).outcome()).isEqualTo(Outcome.PASS);
        Result annual = MinimumWageRule.check(wage("연봉", 24000000), null, MIN_2026);
        assertThat(annual.outcome()).isEqualTo(Outcome.SHORTFALL);
        assertThat(annual.comparison().rightValue()).isEqualTo("25,882,560원");
    }

    @Test
    void 하루_시간만_있고_주_시간을_못_구하면_월급_연봉은_209로_환산하지_않고_건너뛴다() {
        // {hoursPerDay: 4, daysPerWeek: null} 월 100만 원: 209시간으로 나누면 미달로 잘못 나온다
        Result monthly = MinimumWageRule.check(wage("MONTHLY", 1000000), hours(4, null), MIN_2026);
        assertThat(monthly.outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(MinimumWageRule.check(wage("ANNUAL", 12000000), hours(4, null), MIN_2026).outcome())
                .isEqualTo(Outcome.SKIPPED);
        // 근로시간 행은 있는데 값이 없을 때(빈 Map)도 같다
        assertThat(MinimumWageRule.check(wage("MONTHLY", 1000000), Map.of(), MIN_2026).outcome())
                .isEqualTo(Outcome.SKIPPED);
        // 시급·일급은 주 시간이 필요 없다
        assertThat(MinimumWageRule.check(wage("HOURLY", 10000), hours(4, null), MIN_2026).outcome())
                .isEqualTo(Outcome.SHORTFALL);
        assertThat(MinimumWageRule.check(wage("DAILY", 40000), hours(4, null), MIN_2026).outcome())
                .isEqualTo(Outcome.SHORTFALL);
    }

    @Test
    void 한_달_적게_받는_금액도_주_40시간까지만_센다() {
        // 320원 × 40 × 365/84 = 55,619 → 55,600 (주 52시간을 그대로 쓰면 72,300)
        Result r = MinimumWageRule.check(wage("HOURLY", 10000), Map.of("hoursPerWeek", 52), MIN_2026);
        assertThat(r.whyCheck()).contains("주 40시간이면 한 달 약 55,600원");
    }
}
