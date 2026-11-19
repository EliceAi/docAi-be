package com.team.docai.domain.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 질문에서 법령 전문 검색어 뽑기 (아키텍처 "질문으로 검색한 조문"). GPT 를 한 번 더 부르지 않으려고 코드로 한다.
 * 낱말 끝의 조사를 한 번 떼고(위약금을 → 위약금), 두 글자 미만과 흔한 말(계약서, 문제, 괜찮아 ...)은 뺀다.
 * 검색은 낱말 앞부분 일치(LawSearchService)라 남은 어미가 있어도 대체로 맞는다.
 */
final class ChatKeywords {

    static final int MAX_KEYWORDS = 5;

    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    /** 긴 것부터 (에서는 → 에서 → 에 순으로 떼지 않게) */
    private static final List<String> PARTICLES = List.of(
            "에서는", "으로는", "이라도", "에게서", "한테서",
            "에서", "에게", "한테", "으로", "까지", "부터", "이랑", "하고", "처럼", "보다", "이나", "라도", "이요",
            "은", "는", "이", "가", "을", "를", "에", "의", "도", "로", "와", "과", "만", "랑", "요");
    private static final Set<String> STOPWORDS = Set.of(
            "내", "제", "저", "나", "우리", "이거", "그거", "저거", "이것", "그것", "거", "것", "수", "좀", "꼭", "더",
            "계약", "계약서", "문서", "문제", "문제없어", "문제없나", "괜찮아", "괜찮나", "괜찮을까", "괜찮나요",
            "뭐", "뭐야", "무엇", "어떻게", "어떤", "왜", "언제", "어디", "누가", "얼마", "얼마나",
            "해도", "돼", "되나", "되나요", "될까", "있어", "있나", "있나요", "없어", "없나", "없나요",
            "알려줘", "알려", "주세요", "질문", "확인", "조항", "내용", "부분", "여기", "거기", "이건", "그건", "이게", "그게");

    private ChatKeywords() {
    }

    static List<String> extract(String question) {
        List<String> out = new ArrayList<>();
        if (question == null) {
            return out;
        }
        for (String raw : SPLIT.split(question)) {
            if (raw.isEmpty()) {
                continue;
            }
            String word = stripParticle(raw.toLowerCase());
            if (word.codePointCount(0, word.length()) < 2 || STOPWORDS.contains(word) || STOPWORDS.contains(raw)) {
                continue;
            }
            if (!out.contains(word)) {
                out.add(word);
            }
            if (out.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return out;
    }

    private static String stripParticle(String word) {
        for (String p : PARTICLES) {
            if (word.length() > p.length() + 1 && word.endsWith(p)) {
                return word.substring(0, word.length() - p.length());
            }
        }
        return word;
    }
}
