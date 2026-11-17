package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.entity.NumberComparison;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 최저임금 비교 (아키텍처 5단계, rule_code = MIN_WAGE). 계산만 하고 저장하지 않는다.
 * <ul>
 *   <li>임금 value_json {type, amount}: HOURLY(시급) / DAILY(일급) / MONTHLY(월급) / ANNUAL(연봉). 대소문자·한글 이름도 받는다</li>
 *   <li>근로시간 value_json {hoursPerDay, daysPerWeek} (또는 hoursPerWeek)이 있으면 주 소정근로시간으로 월 환산 시간을 계산한다</li>
 *   <li>월 환산 시간 = (주 소정근로시간 + 주휴시간) × 365 / 7 / 12, 반올림. 주 소정근로시간은 40시간까지만 센다,
 *       주휴시간은 주 15시간 이상일 때 주 소정근로시간 / 5. 근로시간 행(value_json)이 없을 때만 주 40시간 기준 209시간.
 *       근로시간 값은 있는데 주 시간을 구할 수 없으면(예: hoursPerDay 만 있음) 단시간일 수 있어 계산하지 않는다 (SKIPPED)</li>
 *   <li>같으면 통과, 기준보다 적을 때만 미달 (amount &lt; 최저임금 × 시간)</li>
 * </ul>
 * 선택 칸 (5단계와 약속): 임금 basePay(수당을 뺀 비교용 금액, 있으면 amount 대신),
 * 근로시간 monthlyHours(계약서에 적힌 월 기준시간, 있으면 월급·연봉 환산에 주 시간 계산보다 우선).
 * WEEKLY(주급)는 주휴수당 포함 여부를 알 수 없어 계산하지 않는다 (SKIPPED).
 */
final class MinimumWageRule {

    static final int DEFAULT_MONTHLY_HOURS = 209;
    private static final BigDecimal FULL_TIME_WEEKLY_HOURS = BigDecimal.valueOf(40);
    private static final BigDecimal WEEKLY_HOLIDAY_THRESHOLD = BigDecimal.valueOf(15);
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);
    private static final BigDecimal WEEKS_MONTHS = BigDecimal.valueOf(7 * 12);

    enum WageType {
        HOURLY("시급", "시간당"), DAILY("일급", "하루"), MONTHLY("월급", "한 달"), ANNUAL("연봉", "1년");

        final String label;
        final String periodPhrase;

        WageType(String label, String periodPhrase) {
            this.label = label;
            this.periodPhrase = periodPhrase;
        }
    }

    enum Outcome { PASS, SHORTFALL, SKIPPED }

    /** 기준값 (rule_param 한 행). */
    record Standard(BigDecimal hourly, LocalDate validFrom, Long lawArticleId) {
        String title() {
            return validFrom.getYear() + "년 최저임금";
        }
    }

    record Result(Outcome outcome, WageType type, NumberComparison comparison, String headline, String whyCheck,
                  String skipReason) {
        static Result skipped(String reason) {
            return new Result(Outcome.SKIPPED, null, null, null, null, reason);
        }
    }

    private MinimumWageRule() {
    }

    static Result check(Map<String, Object> wage, Map<String, Object> hours, Standard standard) {
        if (wage == null) {
            return Result.skipped("임금 value_json 없음");
        }
        Optional<WageType> type = wageType(wage.get("type"));
        if (type.isEmpty()) {
            return Result.skipped("계산할 수 없는 임금 종류");
        }
        // basePay: 최저임금 비교용 금액 (연장·야간·휴일 수당 제외). 있으면 amount 대신 쓴다
        Optional<BigDecimal> basePay = RuleValues.positive(wage, "basePay");
        Optional<BigDecimal> amount = basePay.or(() -> RuleValues.positive(wage, "amount", "value"));
        if (amount.isEmpty()) {
            return Result.skipped("임금 금액 없음");
        }
        Optional<BigDecimal> hoursPerDay = RuleValues.positive(hours, "hoursPerDay");
        Optional<BigDecimal> weekly = weeklyHours(hours);
        // monthlyHours: 계약서에 적힌 월 기준시간. 주 시간 계산보다 우선
        Optional<BigDecimal> writtenMonthly = RuleValues.positive(hours, "monthlyHours");
        BigDecimal min = standard.hourly();
        boolean monthBased = type.get() == WageType.MONTHLY || type.get() == WageType.ANNUAL;
        if (monthBased && writtenMonthly.isEmpty() && weekly.isEmpty() && hours != null) {
            // 209시간은 근로시간 행이 아예 없을 때만. 하루 시간만 있으면 단시간일 수 있어 209로 나누면 미달로 잘못 나온다
            return Result.skipped("근로시간 값은 있으나 주 소정근로시간을 구할 수 없음");
        }

        BigDecimal hoursBasis;
        String basisText;
        switch (type.get()) {
            case HOURLY -> {
                hoursBasis = BigDecimal.ONE;
                basisText = null;
            }
            case DAILY -> {
                if (hoursPerDay.isEmpty()) {
                    return Result.skipped("일급인데 하루 근로시간 없음");
                }
                hoursBasis = hoursPerDay.get();
                basisText = "하루 " + RuleValues.plain(hoursBasis) + "시간 기준";
            }
            case MONTHLY -> {
                hoursBasis = writtenMonthly.orElseGet(() -> BigDecimal.valueOf(monthlyHours(weekly.orElse(null))));
                basisText = "월 " + RuleValues.plain(hoursBasis) + "시간 기준";
            }
            case ANNUAL -> {
                BigDecimal monthly = writtenMonthly.orElseGet(() -> BigDecimal.valueOf(monthlyHours(weekly.orElse(null))));
                hoursBasis = monthly.multiply(BigDecimal.valueOf(12));
                basisText = "월 " + RuleValues.plain(monthly) + "시간 × 12개월 기준";
            }
            default -> throw new IllegalStateException();
        }
        BigDecimal threshold = min.multiply(hoursBasis);
        if (amount.get().compareTo(threshold) >= 0) {
            return new Result(Outcome.PASS, type.get(), null, null, null, null);
        }
        WageType t = type.get();
        String leftLabel = "계약서 " + t.label + (basePay.isPresent() ? " (수당 제외)" : "");
        NumberComparison comparison = t == WageType.HOURLY
                ? new NumberComparison(leftLabel, RuleValues.won(amount.get()), "<", standard.title(), RuleValues.won(min))
                : new NumberComparison(leftLabel, RuleValues.won(amount.get()), "<",
                        standard.title() + " 환산 (" + basisText + ")", RuleValues.won(threshold));
        String headline = t.label + "이 " + standard.title() + "보다 낮아요";
        return new Result(Outcome.SHORTFALL, t, comparison, headline,
                whyCheck(t, amount.get(), min, threshold, hoursBasis, basisText, weekly.orElse(null), standard), null);
    }

    /** 월 환산 시간. 주 소정근로시간을 모르면 209. */
    static int monthlyHours(BigDecimal weeklyHours) {
        if (weeklyHours == null) {
            return DEFAULT_MONTHLY_HOURS;
        }
        BigDecimal w = weeklyHours.min(FULL_TIME_WEEKLY_HOURS);
        BigDecimal holiday = w.compareTo(WEEKLY_HOLIDAY_THRESHOLD) >= 0
                ? w.divide(BigDecimal.valueOf(5), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return w.add(holiday).multiply(DAYS_PER_YEAR).divide(WEEKS_MONTHS, 0, RoundingMode.HALF_UP).intValueExact();
    }

    static Optional<BigDecimal> weeklyHours(Map<String, Object> hours) {
        Optional<BigDecimal> direct = RuleValues.positive(hours, "hoursPerWeek", "weeklyHours");
        if (direct.isPresent()) {
            return direct;
        }
        Optional<BigDecimal> perDay = RuleValues.positive(hours, "hoursPerDay");
        Optional<BigDecimal> days = RuleValues.positive(hours, "daysPerWeek");
        if (perDay.isPresent() && days.isPresent()) {
            return Optional.of(perDay.get().multiply(days.get()));
        }
        return Optional.empty();
    }

    static Optional<WageType> wageType(Object raw) {
        if (!(raw instanceof String s)) {
            return Optional.empty();
        }
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "HOURLY", "HOUR", "시급" -> Optional.of(WageType.HOURLY);
            case "DAILY", "DAY", "일급", "일당" -> Optional.of(WageType.DAILY);
            case "MONTHLY", "MONTH", "월급" -> Optional.of(WageType.MONTHLY);
            case "ANNUAL", "YEARLY", "YEAR", "연봉" -> Optional.of(WageType.ANNUAL);
            default -> Optional.empty();
        };
    }

    private static String whyCheck(WageType t, BigDecimal amount, BigDecimal min, BigDecimal threshold,
                                   BigDecimal hoursBasis, String basisText, BigDecimal weekly, Standard standard) {
        BigDecimal diff = threshold.subtract(amount);
        if (t == WageType.HOURLY) {
            StringBuilder sb = new StringBuilder("계약서 시급이 ").append(standard.title()).append(' ')
                    .append(RuleValues.won(min)).append("보다 시간당 ").append(RuleValues.won(diff)).append(" 낮아요.");
            if (weekly != null) {
                weekly = weekly.min(FULL_TIME_WEEKLY_HOURS);
                BigDecimal monthlyLoss = diff.multiply(weekly).multiply(DAYS_PER_YEAR)
                        .divide(WEEKS_MONTHS, 0, RoundingMode.DOWN)
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).multiply(BigDecimal.valueOf(100));
                if (monthlyLoss.signum() > 0) {
                    sb.append(" 주 ").append(RuleValues.plain(weekly)).append("시간이면 한 달 약 ")
                            .append(RuleValues.won(monthlyLoss)).append(" 적게 받아요.");
                }
            }
            return sb.toString();
        }
        BigDecimal hourly = amount.divide(hoursBasis, 0, RoundingMode.DOWN);
        return t.label + "을 시간당으로 바꾸면 약 " + RuleValues.won(hourly) + "(" + basisText + ")으로, "
                + standard.title() + " " + RuleValues.won(min) + "보다 낮아요. " + t.periodPhrase + " 약 "
                + RuleValues.won(diff) + " 적어요.";
    }
}
