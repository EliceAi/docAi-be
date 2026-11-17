package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.entity.Analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 규칙 계산용 값 읽기. GPT 1차가 준 value_json 은 모양이 조금씩 다를 수 있어(숫자/글자 "2,500,000", 대소문자) 여기서 한 번 고른다.
 * 계산은 value_json 의 숫자로만 한다 (ERD analysis_key_term). 화면 글자(value)는 읽지 않는다.
 */
final class RuleValues {

    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Pattern NUMBER_NOISE = Pattern.compile("[,\\s원]|KRW|krw");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private RuleValues() {
    }

    /** 법 적용 기준일: analysis.law_base_date(계약일), 없으면 분석 시작일(한국 날짜). */
    static LocalDate baseDate(Analysis analysis) {
        if (analysis.getLawBaseDate() != null) {
            return analysis.getLawBaseDate();
        }
        return analysis.getStartedAt().atZoneSameInstant(KOREA).toLocalDate();
    }

    /** 0보다 큰 숫자. 숫자·"2,500,000"·"10000원" 을 받는다. 그 밖(null, 빈 글자, 음수, 0)은 empty. */
    static Optional<BigDecimal> positive(Object value) {
        BigDecimal n = null;
        if (value instanceof BigDecimal b) {
            n = b;
        } else if (value instanceof Number num) {
            n = new BigDecimal(num.toString());
        } else if (value instanceof String s) {
            String cleaned = NUMBER_NOISE.matcher(s).replaceAll("");
            if (!cleaned.isEmpty()) {
                try {
                    n = new BigDecimal(cleaned);
                } catch (NumberFormatException e) {
                    n = null;
                }
            }
        }
        return n != null && n.signum() > 0 ? Optional.of(n) : Optional.empty();
    }

    static Optional<BigDecimal> positive(Map<String, Object> json, String... keys) {
        if (json == null) {
            return Optional.empty();
        }
        for (String key : keys) {
            Optional<BigDecimal> n = positive(json.get(key));
            if (n.isPresent()) {
                return n;
            }
        }
        return Optional.empty();
    }

    /** 라벨 비교용: 공백을 모두 뺀다 ("근무 장소" = "근무장소"). */
    static String normalizeLabel(String label) {
        return label == null ? "" : WHITESPACE.matcher(label).replaceAll("");
    }

    /** "2,156,880원" (소수점 아래는 버림). */
    static String won(BigDecimal amount) {
        return String.format("%,d원", amount.setScale(0, RoundingMode.DOWN).longValueExact());
    }

    /** 시간 수 표시: 20 → "20", 7.5 → "7.5". */
    static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }
}
