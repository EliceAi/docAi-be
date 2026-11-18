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
 * </ul>
 */
public final class ClauseKeyText {

    static final Pattern KEY = Pattern.compile("(?<![A-Za-z0-9_])c(\\d+)(?![A-Za-z0-9_])(\\s*조항|조)?");

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
            String replaced = name + suffix(name, m.group(2));
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
