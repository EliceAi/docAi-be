package com.team.docai.domain.analysis.service;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 가 쓴 글 속 내부 조항 키(c1, c12) 바꾸기. 1차 병합(ExtractMerger)과 2차 검증(JudgeValidator)이 같이 쓴다.
 * <ul>
 *   <li>키는 소문자 c + 숫자. 앞뒤가 영문·숫자·_ 이면 다른 낱말의 일부라 보지 않는다 (abc1, c1a, 1c2, C1 등급).
 *       한글·전각 문자·괄호·쉼표가 붙은 것("c5에서", "（c1）", "c1,c2")은 키로 본다.</li>
 *   <li>숫자는 정수로 맞춰 찾는다 (c05 → c5). 이름을 못 찾으면(이 분석에 없는 키, 너무 큰 수) 원문 그대로 둔다.</li>
 *   <li>말 겹침 방지: 키 바로 뒤의 "조항"·"조"는 이름이 이미 "조항"으로 끝나면 버리고("c1 조항" → "「임금」 조항"),
 *       이름이 "조"로 끝나면 "조"는 버리고 "조항"은 띄어 남긴다("c5조" → "제5조", "c5조항" → "제5조 조항").</li>
 *   <li>바로 뒤 조사는 이름의 받침(숫자로 끝나면 읽는 소리)에 맞춘다("c7은" → "제7조는", "c1과" → "「임금」 조항과",
 *       "c3은" → "제10조의2는"). 조사 뒤에 의·요·는·도·만이 붙어도 맞춘다("c7은요" → "제7조는요", "c7과의" → "제7조와의").
 *       그 밖의 한글이 더 붙으면("c7이다") 조사로 보지 않고 그대로 둔다. 띄어 쓴 "c7 조는" → "제7조는".</li>
 * </ul>
 */
public final class ClauseKeyText {

    static final Pattern KEY = Pattern.compile(
            "(?<![A-Za-z0-9_])c(\\d+)(?![A-Za-z0-9_])(\\s*조항|\\s*조)?"
                    + "(?:(으로|로|은|는|을|를|과|와)(?=[의요는도만]?(?![가-힣]))|(이|가)(?=[의는도만]?(?![가-힣])))?");

    private ClauseKeyText() {
    }

    /**
     * @param nameOf    정규화한 키("c5") → 바꿀 이름. null 이면 바꾸지 않는다
     * @param onReplace (찾은 글, 바꾼 글) 마다 불린다 (기록용)
     */
    public static String replace(String text, Function<String, String> nameOf, BiConsumer<String, String> onReplace) {
        if (text == null || text.indexOf('c') < 0) {
            return text;
        }
        Matcher m = KEY.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            String name = null;
            try {
                name = nameOf.apply("c" + Integer.parseInt(m.group(1)));
            } catch (NumberFormatException e) {
                // 너무 큰 수: 키가 아님
            }
            if (name == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String josa = m.group(3) != null ? m.group(3) : m.group(4);
            String following = m.group(2);
            String replaced;
            if (following != null && Character.isWhitespace(following.charAt(0)) && josa == null
                    && m.end() < text.length() && isHangul(text.charAt(m.end()))) {
                // "c7 조정": 띄어 쓴 "조" 뒤에 한글이 이어지면 조 번호가 아니라 다른 낱말. 키만 바꾸고 뒤는 그대로
                replaced = name + m.group().substring(m.end(1) - m.start());
            } else {
                String base = name + suffix(name, following);
                replaced = base + josa(base, josa);
            }
            onReplace.accept(m.group(), replaced);
            m.appendReplacement(sb, Matcher.quoteReplacement(replaced));
            changed = true;
        }
        if (!changed) {
            return text;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 바꾼 이름 뒤 조사를 받침에 맞춘다 (은/는, 이/가, 을/를, 과/와, 으로/로). "으로/로" 는 받침 ㄹ 이면 "로".
     * 이름이 숫자로 끝나면(제10조의2) 숫자 읽는 소리로 정한다: 0·1·3·6·7·8 은 받침 있음(그중 1·7·8 은 ㄹ),
     * 2·4·5·9 는 받침 없음. 한글·숫자가 아닌 글자로 끝나면 원래 조사를 그대로 둔다.
     */
    static String josa(String name, String josa) {
        if (josa == null) {
            return "";
        }
        char last = name.charAt(name.length() - 1);
        boolean batchim;
        boolean rieul;
        if (isHangul(last)) {
            int jong = (last - '가') % 28;
            batchim = jong != 0;
            rieul = jong == 8;
        } else if (last >= '0' && last <= '9') {
            batchim = "013678".indexOf(last) >= 0;
            rieul = "178".indexOf(last) >= 0;
        } else {
            return josa;
        }
        return switch (josa) {
            case "은", "는" -> batchim ? "은" : "는";
            case "이", "가" -> batchim ? "이" : "가";
            case "을", "를" -> batchim ? "을" : "를";
            case "과", "와" -> batchim ? "과" : "와";
            case "으로", "로" -> batchim && !rieul ? "으로" : "로";
            default -> josa;
        };
    }

    private static boolean isHangul(char c) {
        return c >= '가' && c <= '힣';
    }

    private static String suffix(String name, String following) {
        if (following == null) {
            return "";
        }
        boolean clauseWord = following.strip().equals("조항");
        if (name.endsWith("조항")) {
            return "";
        }
        if (name.endsWith("조")) {
            return clauseWord ? " 조항" : "";
        }
        return following;
    }
}
