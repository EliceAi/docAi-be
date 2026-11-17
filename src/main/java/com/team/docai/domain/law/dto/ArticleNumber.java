package com.team.docai.domain.law.dto;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 조문 번호 (조, 가지, 항). GPT 법령 힌트·사용자 글자를 DB 칸 (article_no, article_branch_no, paragraph_no) 으로 맞춘다 (설계_점검 30번).
 * <ul>
 *   <li>"조"가 있어야 한다: "제10조의2" → (10, 2), "제17조 제1항" → (17, 0, 1), "제17조제1항제1호" → (17, 0, 1), "제20조(위약 예정의 금지)" → (20, 0)</li>
 *   <li>숫자만: "20" → (20, 0). 가지번호 표기 "10-2" → (10, 2)</li>
 *   <li>거절: "제2장", "제3항", "2024년", "10의2" 처럼 "조"가 없는 글자</li>
 * </ul>
 */
public record ArticleNumber(int articleNo, int branchNo, Integer paragraphNo) {

    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\s*(\\d{1,4})\\s*$");
    private static final Pattern DASH_BRANCH = Pattern.compile("^\\s*(\\d{1,4})\\s*-\\s*(\\d{1,2})\\s*$");
    private static final Pattern WITH_JO = Pattern.compile(
            "^\\s*(?:제\\s*)?(\\d{1,4})\\s*조(?:\\s*의\\s*(\\d{1,2}))?"   // 제10조의2
                    + "(?:\\s*(?:제\\s*)?(\\d{1,3})\\s*항)?"                // 제1항
                    + "(?:\\s*(?:제\\s*)?\\d{1,3}\\s*호(?:\\s*의\\s*\\d{1,2})?)?"  // 제1호 (항까지만 쓴다)
                    + "(?:\\s*[가-힣]\\s*목)?"                               // 가목
                    + "(?:\\s*\\(.*\\))?\\s*$", Pattern.DOTALL);            // (조문 제목)

    public static Optional<ArticleNumber> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = DIGITS_ONLY.matcher(text);
        if (m.matches()) {
            return Optional.of(new ArticleNumber(Integer.parseInt(m.group(1)), 0, null));
        }
        m = DASH_BRANCH.matcher(text);
        if (m.matches()) {
            return Optional.of(new ArticleNumber(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), null));
        }
        m = WITH_JO.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        int no = Integer.parseInt(m.group(1));
        int branch = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        Integer paragraph = m.group(3) == null ? null : Integer.parseInt(m.group(3));
        return Optional.of(new ArticleNumber(no, branch, paragraph));
    }

    /** "제10조의2", 항이 있으면 "제17조제1항". */
    public String label() {
        return label(articleNo, branchNo, paragraphNo);
    }

    public static String label(int articleNo, int branchNo, Integer paragraphNo) {
        StringBuilder sb = new StringBuilder("제").append(articleNo).append("조");
        if (branchNo > 0) {
            sb.append("의").append(branchNo);
        }
        if (paragraphNo != null) {
            sb.append("제").append(paragraphNo).append("항");
        }
        return sb.toString();
    }
}
