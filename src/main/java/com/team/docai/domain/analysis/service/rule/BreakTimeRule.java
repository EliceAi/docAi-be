package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.entity.NumberComparison;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 휴게시간 (근로기준법 제54조 제1항: 근로시간이 4시간인 경우 30분 이상, 8시간인 경우 1시간 이상의 휴게시간을 근로시간 도중에).
 * 계산만 하고 저장하지 않는다. 꼬리표 NO_BREAK_TIME.
 * <ul>
 *   <li>근로시간 value_json 에서 읽는다: workStart·workEnd("HH:mm"), breaks([{start, end}]), breakMinutes(분), noBreak(true),
 *       hoursPerDay(휴게 뺀 하루 소정근로시간)</li>
 *   <li>휴게(분) = breaks 구간 합 → 없으면 breakMinutes → 없으면 noBreak = true 일 때 0 → 그래도 없으면 모름 (판정 안 함).
 *       noBreak 인데 휴게 값도 있으면 서로 어긋나 판정 안 함</li>
 *   <li>하루 근로시간(분) = 종업 − 시업 − 휴게 (종업이 시업보다 이르거나 같으면 자정을 넘긴 근무로 보고 24시간을 더한다.
 *       같은 시각이면 24시간 근무인지 알 수 없어 판정 안 함). 시각이 없으면 hoursPerDay × 60.
 *       시각과 hoursPerDay 가 둘 다 있는데 30분 넘게 다르면 판정 안 함 (근로시간이 서로 맞지 않음)</li>
 *   <li>필요 휴게: 근로 480분(8시간) 이상 60분, 240분(4시간) 이상 30분, 그 미만 의무 없음. 경계(정확히 4·8시간) 포함</li>
 *   <li>휴게 &lt; 필요 휴게 → SHORTFALL. 휴게가 "근로시간 도중"에 있는지는 보지 않는다</li>
 * </ul>
 * 기준값은 법 조문 자체(개정 드묾)라 rule_param 이 아니라 상수로 둔다.
 */
final class BreakTimeRule {

    static final int FOUR_HOURS = 4 * 60;
    static final int EIGHT_HOURS = 8 * 60;
    static final int BREAK_FOR_FOUR_HOURS = 30;
    static final int BREAK_FOR_EIGHT_HOURS = 60;
    private static final int DAY = 24 * 60;
    /** 시각으로 계산한 근로 분과 hoursPerDay 가 이보다 더 다르면 판정하지 않는다 (분) */
    static final int HOURS_MISMATCH_TOLERANCE = 30;
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");

    enum Outcome { PASS, SHORTFALL, SKIPPED }

    record Result(Outcome outcome, NumberComparison comparison, String headline, String whyCheck, String skipReason) {
        static Result skipped(String reason) {
            return new Result(Outcome.SKIPPED, null, null, null, reason);
        }

        static Result pass() {
            return new Result(Outcome.PASS, null, null, null, null);
        }
    }

    private BreakTimeRule() {
    }

    static Result check(Map<String, Object> hours) {
        if (hours == null || hours.isEmpty()) {
            return Result.skipped("근로시간 value_json 없음");
        }
        Optional<Integer> start = minutes(hours.get("workStart"));
        Optional<Integer> end = minutes(hours.get("workEnd"));
        boolean noBreak = Boolean.TRUE.equals(hours.get("noBreak"));
        Optional<Integer> rangeBreak = rangeBreakMinutes(hours.get("breaks"));
        if (rangeBreak.isPresent() && rangeBreak.get() < 0) {
            return Result.skipped("휴게 구간을 읽을 수 없음");
        }
        Optional<Integer> writtenBreak = RuleValues.positive(hours, "breakMinutes")
                .map(b -> b.setScale(0, RoundingMode.DOWN).intValue());
        Optional<Integer> breakMinutes = rangeBreak.or(() -> writtenBreak);
        if (noBreak && breakMinutes.isPresent()) {
            return Result.skipped("휴게 없음과 휴게시간이 함께 적혀 있음");
        }
        if (noBreak) {
            breakMinutes = Optional.of(0);
        }
        if (breakMinutes.isEmpty()) {
            return Result.skipped("휴게시간을 알 수 없음");
        }
        int brk = breakMinutes.get();

        int work;
        String workBasis;
        if (start.isPresent() && end.isPresent()) {
            int span = end.get() - start.get();
            if (span == 0) {
                return Result.skipped("시업과 종업 시각이 같음");
            }
            if (span < 0) {
                span += DAY;
            }
            work = span - brk;
            if (work <= 0) {
                return Result.skipped("휴게시간이 근무 시간보다 김");
            }
            Optional<BigDecimal> perDay = RuleValues.positive(hours, "hoursPerDay");
            if (perDay.isPresent() && Math.abs(work - toMinutes(perDay.get())) > HOURS_MISMATCH_TOLERANCE) {
                // 시각을 잘못 읽었거나 하루 시간이 다른 뜻(교대·요일별)일 수 있어 판정하지 않는다
                return Result.skipped("근로시간이 서로 맞지 않음 (시각 기준 " + work + "분, hoursPerDay "
                        + RuleValues.plain(perDay.get()) + "시간)");
            }
            workBasis = text(start.get()) + "~" + text(end.get());
        } else {
            Optional<BigDecimal> perDay = RuleValues.positive(hours, "hoursPerDay");
            if (perDay.isEmpty()) {
                return Result.skipped("하루 근로시간을 알 수 없음");
            }
            work = toMinutes(perDay.get());
            if (work <= 0 || work > DAY) {
                return Result.skipped("하루 근로시간이 맞지 않음");
            }
            workBasis = null;
        }
        int required = required(work);
        if (required == 0 || brk >= required) {
            return Result.pass();
        }
        String workText = duration(work);
        NumberComparison comparison = new NumberComparison("계약서 휴게시간", duration(brk), "<",
                "법정 최소 휴게시간 (하루 근로 " + workText + ")", duration(required));
        String headline = brk == 0 ? "하루 " + workText + " 일하는데 휴게시간이 없어요"
                : "하루 " + workText + " 일하는데 휴게시간이 " + duration(required) + "보다 짧아요";
        StringBuilder why = new StringBuilder();
        if (workBasis != null) {
            why.append(workBasis).append(brk == 0 ? " 근무로 " : " 근무에서 휴게 " + duration(brk) + "을 빼면 ");
        }
        why.append("하루 근로시간이 ").append(workText).append("이에요. 근로시간이 ")
                .append(required == BREAK_FOR_EIGHT_HOURS ? "8시간이면 1시간" : "4시간이면 30분")
                .append(" 이상 휴게시간을 근로시간 도중에 주도록 정해져 있는데, 계약서에는 ")
                .append(brk == 0 ? "휴게시간이 없어요." : "휴게시간이 " + duration(brk) + "이에요.");
        return new Result(Outcome.SHORTFALL, comparison, headline, why.toString(), null);
    }

    private static int toMinutes(BigDecimal hours) {
        return hours.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** 근로 분 → 필요한 휴게 분 */
    static int required(int workMinutes) {
        if (workMinutes >= EIGHT_HOURS) {
            return BREAK_FOR_EIGHT_HOURS;
        }
        if (workMinutes >= FOUR_HOURS) {
            return BREAK_FOR_FOUR_HOURS;
        }
        return 0;
    }

    /** "HH:mm" → 자정부터의 분. 24:00 은 1440 */
    static Optional<Integer> minutes(Object raw) {
        if (!(raw instanceof String s)) {
            return Optional.empty();
        }
        Matcher m = TIME.matcher(s.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h < 24 && min < 60 || h == 24 && min == 0) {
            return Optional.of(h * 60 + min);
        }
        return Optional.empty();
    }

    /** breaks 구간 합(분). 없으면 empty, 읽을 수 없는 구간이 있으면 -1 (판정 안 함) */
    private static Optional<Integer> rangeBreakMinutes(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Optional.empty();
        }
        int sum = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> range)) {
                return Optional.of(-1);
            }
            Optional<Integer> s = minutes(range.get("start"));
            Optional<Integer> e = minutes(range.get("end"));
            if (s.isEmpty() || e.isEmpty() || s.get().equals(e.get())) {
                return Optional.of(-1);
            }
            int span = e.get() - s.get();
            sum += span < 0 ? span + DAY : span;
        }
        return Optional.of(sum);
    }

    private static String text(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    /** 90 → "1시간 30분", 60 → "1시간", 30 → "30분", 0 → "0분" */
    static String duration(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) {
            return m + "분";
        }
        return m == 0 ? h + "시간" : h + "시간 " + m + "분";
    }
}
