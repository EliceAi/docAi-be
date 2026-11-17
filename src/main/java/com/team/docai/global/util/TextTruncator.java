package com.team.docai.global.util;

/**
 * 글자 수(유니코드 코드 포인트) 기준 자르기. PostgreSQL VARCHAR(n) 의 n 은 글자 수이므로
 * String.length()(UTF-16 단위)가 아니라 코드 포인트로 센다. 이모지 같은 서로게이트 쌍을 반으로 자르지 않는다.
 * 말줄임표는 붙이지 않는다. 잘랐다는 기록(validation_log_json)은 부르는 쪽이 {@link #exceeds}로 확인해 남긴다.
 */
public final class TextTruncator {

    private TextTruncator() {
    }

    /**
     * @return value 가 max 글자를 넘으면 앞 max 글자, 아니면 그대로. null 은 null.
     */
    public static String truncate(String value, int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must be >= 0: " + max);
        }
        if (!exceeds(value, max)) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    /**
     * @return value 가 max 글자(코드 포인트)를 넘는지. null 은 false.
     */
    public static boolean exceeds(String value, int max) {
        if (value == null || value.length() <= max) {
            // 코드 포인트 수는 length() 이하이므로 length() <= max 면 넘지 않는다
            return false;
        }
        return value.codePointCount(0, value.length()) > max;
    }
}
