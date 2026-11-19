package com.team.docai.domain.analysis.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 가 쓴 글의 단정 표현 거르기 (아키텍처 8단계 "무효", "불법", "서명해도 된다" 같은 단정 표현 차단)와 문장 나누기.
 * 2차 검증(JudgeValidator)과 챗봇 답변 검증(ChatAnswerValidator)이 같은 규칙을 쓴다.
 * 지시문에서 쓰지 말라고 했지만 지켜진다는 보장이 없어 서버가 한 번 더 거른다.
 */
public final class AssertionText {

    /** 단정 표현 */
    public static final Pattern ASSERTION = Pattern.compile(
            "무효|불법|위법|효력이\\s*없|"
                    + "위반(?:이다|입니다|이에요|이야|임|된다|됩니다|돼요|되요|돼|한다|합니다|해요|하는\\s*조항)|"
                    + "서명(?:해|하셔)도\\s*(?:된다|됩니다|돼요|되요|돼|괜찮)");

    private static final String END_MARKS = ".!?…";
    /** 문장 끝 부호 뒤에 붙는 닫는 따옴표·괄호 (그 뒤까지 한 문장) */
    private static final String CLOSERS = "\"'”’)」』]";
    /** 지운 뒤 홀로 남은 번호 조각 ("1.", "2)", "(3)") */
    private static final Pattern NUMBER_FRAGMENT = Pattern.compile("^\\(?\\d{1,3}[.)]?$");

    /**
     * 문장 하나와 그 뒤 공백.
     *
     * @param separator 다음 문장 앞까지의 공백 (줄바꿈 포함 가능, 마지막 문장이면 "")
     */
    public record Sentence(String text, String separator) {
    }

    private AssertionText() {
    }

    /** 단정 표현이 들어 있는지 */
    public static boolean contains(String text) {
        return text != null && ASSERTION.matcher(text).find();
    }

    /**
     * 문장 나누기.
     * <ul>
     *   <li>마침표·물음표·느낌표(이어진 것은 한 덩어리)와 그 뒤 닫는 따옴표·괄호까지가 한 문장. 뒤에 공백이 없어도
     *       나눈다 ("있어요.그리고" → 두 문장).</li>
     *   <li>마침표 앞뒤가 모두 숫자면 나누지 않는다 ("2026.11.12", "10.5%").</li>
     *   <li>줄바꿈도 문장 끝.</li>
     * </ul>
     */
    public static List<Sentence> split(String text) {
        List<Sentence> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int n = text.length();
        int start = skipSpaces(text, 0);
        int i = start;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n') {
                int next = skipSpaces(text, i);
                add(out, text.substring(start, i), text.substring(i, next));
                start = i = next;
                continue;
            }
            if (END_MARKS.indexOf(c) >= 0) {
                if (c == '.' && i > 0 && i + 1 < n
                        && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1))) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < n && END_MARKS.indexOf(text.charAt(j)) >= 0) {
                    j++;
                }
                while (j < n && CLOSERS.indexOf(text.charAt(j)) >= 0) {
                    j++;
                }
                int next = skipSpaces(text, j);
                add(out, text.substring(start, j), text.substring(j, next));
                start = i = next;
                continue;
            }
            i++;
        }
        if (start < n) {
            add(out, text.substring(start), "");
        }
        return out;
    }

    /**
     * drop 이 true 인 문장을 지운다. 지운 것이 없으면 앞뒤 공백만 지운 원래 글, 다 지워지면 null.
     * <ul>
     *   <li>지운 문장이 사이에 없는 나눔은 원래 공백(빈 문자열 포함)을 그대로 쓴다 ("2026. 11. 12.부터" 가 바뀌지 않게).</li>
     *   <li>지운 자리는 앞뒤에 줄바꿈이 있었으면 줄바꿈, 아니면 공백 하나로 잇는다.</li>
     *   <li>번호 조각("2.")은 줄 처음(글 처음, 또는 앞 나눔에 줄바꿈)이고 바로 뒤 문장을 지웠을 때만 같이 버린다.
     *       줄 중간의 날짜 조각("2026. 11. 12.")은 반만 지워지지 않게 그대로 둔다.</li>
     * </ul>
     */
    public static String removeSentences(String text, Predicate<String> drop) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<Sentence> sentences = split(text);
        int size = sentences.size();
        boolean[] removed = new boolean[size];
        boolean any = false;
        for (int k = 0; k < size; k++) {
            if (drop.test(sentences.get(k).text())) {
                removed[k] = true;
                any = true;
            }
        }
        if (!any) {
            return text.trim();
        }
        for (int k = 0; k < size; k++) {
            boolean lineStart = k == 0 || sentences.get(k - 1).separator().contains("\n");
            if (!removed[k] && lineStart && NUMBER_FRAGMENT.matcher(sentences.get(k).text()).matches()
                    && (k + 1 >= size || removed[k + 1])) {
                removed[k] = true;
            }
        }
        StringBuilder sb = new StringBuilder();
        String originalSeparator = null;
        boolean gap = false;
        boolean gapNewline = false;
        for (int k = 0; k < size; k++) {
            Sentence s = sentences.get(k);
            if (!removed[k]) {
                if (sb.length() > 0) {
                    if (!gap) {
                        sb.append(originalSeparator);
                    } else {
                        sb.append(gapNewline || originalSeparator.contains("\n") ? "\n" : " ");
                    }
                }
                sb.append(s.text());
                originalSeparator = s.separator();
                gap = false;
                gapNewline = false;
            } else if (sb.length() > 0) {
                gap = true;
                gapNewline |= s.separator().contains("\n");
            }
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    /**
     * 단정 표현이 든 문장만 지운다. 지운 것이 없으면 앞뒤 공백만 지운 원래 글, 다 지워지면 null.
     *
     * @param onRemoved (찾은 표현, 지운 문장) 마다 불린다 (기록용)
     */
    public static String removeSentences(String text, BiConsumer<String, String> onRemoved) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (!contains(text)) {
            return text.trim();
        }
        return removeSentences(text, (Predicate<String>) s -> {
            Matcher m = ASSERTION.matcher(s);
            if (m.find()) {
                onRemoved.accept(m.group(), s);
                return true;
            }
            return false;
        });
    }

    private static void add(List<Sentence> out, String text, String separator) {
        String t = text.strip();
        if (!t.isEmpty()) {
            out.add(new Sentence(t, separator));
        } else if (!out.isEmpty() && separator.contains("\n")) {
            Sentence last = out.remove(out.size() - 1);
            out.add(new Sentence(last.text(), last.separator() + separator));
        }
    }

    private static int skipSpaces(String text, int from) {
        int k = from;
        while (k < text.length() && Character.isWhitespace(text.charAt(k))) {
            k++;
        }
        return k;
    }
}
