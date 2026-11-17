package com.team.docai.domain.law.entity;

import java.util.regex.Pattern;

/**
 * 법령 이름·약칭 정규화. law.name_norm, law_alias.alias_norm 은 모두 이 값으로 비교한다.
 * 띄어쓰기·가운뎃점·괄호·기호를 뺀 글자. 법제처가 가운뎃점으로 쓰는 "ㆍ"(U+318D)는 한글 문자로 분류되므로 따로 뺀다.
 * (DB_AI_흐름 "비교용 이름"). 예: "근로자퇴직급여 보장법" → "근로자퇴직급여보장법".
 */
public final class LawNames {

    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]|[\\u318D\\u00B7\\u2027\\u30FB]");

    private LawNames() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        return NOT_LETTER_OR_DIGIT.matcher(name).replaceAll("");
    }
}
