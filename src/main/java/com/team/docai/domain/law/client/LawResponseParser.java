package com.team.docai.domain.law.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 법제처 현행법령 JSON 응답 파서 (2026-11-17 실제 응답으로 확인한 모양).
 * <ul>
 *   <li>목록: {@code LawSearch.law} 가 배열 또는 (1건이면) 객체.</li>
 *   <li>본문: {@code 법령.기본정보}, {@code 법령.조문.조문단위[]}. 조문여부 = "전문"(장·절 제목)은 버린다.</li>
 *   <li>조문가지번호는 0 이면 필드가 없다. 항번호는 "①" 같은 원문자다.</li>
 *   <li>항은 배열, 또는 항번호 없이 호만 담은 객체(본문 + 각 호 조문)로 온다. 호·목도 배열 또는 객체.</li>
 *   <li>"제35조 삭제 &lt;2019.1.15&gt;" 처럼 항 없이 삭제 표시만 있는 조문, "③ 삭제" 항, "3. 삭제" 호·목 줄은 저장하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LawResponseParser {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern DELETED_ARTICLE = Pattern.compile("^제\\s*\\d+\\s*조(?:\\s*의\\s*\\d+)?\\s*삭제(?:\\s|<|$).*", Pattern.DOTALL);
    private static final Pattern DELETED_PARAGRAPH = Pattern.compile("^\\S{1,4}\\s*삭제\\s*(?:<[^>]*>)?\\s*$");
    /** "3. 삭제 <2010.6.4>", "9의2. 삭제", "가. 삭제" 같은 삭제된 호·목 줄 */
    private static final Pattern DELETED_ITEM = Pattern.compile("^\\S{1,8}\\s*삭제\\s*(?:<[^>]*>)?\\s*$");
    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    private final ObjectMapper objectMapper;

    public LawListPage parseList(String json) {
        JsonNode search = read(json).path("LawSearch");
        if (search.isMissingNode()) {
            throw new IllegalArgumentException("현행법령 목록 응답이 아닙니다.");
        }
        int total = parseInt(text(search.get("totalCnt")), 0);
        List<LawListItem> items = new ArrayList<>();
        for (JsonNode n : asList(search.get("law"))) {
            items.add(new LawListItem(
                    text(n.get("법령일련번호")),
                    text(n.get("법령ID")),
                    text(n.get("법령명한글")),
                    blankToNull(text(n.get("법령약칭명"))),
                    text(n.get("법령구분명")),
                    text(n.get("현행연혁코드")),
                    parseDate(text(n.get("공포일자"))),
                    parseDate(text(n.get("시행일자")))));
        }
        return new LawListPage(total, items);
    }

    public ParsedLaw parseBody(String json) {
        JsonNode law = read(json).path("법령");
        JsonNode info = law.path("기본정보");
        if (law.isMissingNode() || info.isMissingNode()) {
            throw new IllegalArgumentException("현행법령 본문 응답이 아닙니다.");
        }
        String name = text(info.get("법령명_한글"));
        LocalDate effectiveDate = parseDate(text(info.get("시행일자")));
        if (name == null || effectiveDate == null) {
            throw new IllegalArgumentException("본문 기본정보에 법령명 또는 시행일자가 없습니다.");
        }

        List<ParsedArticle> articles = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int deletedArticles = 0;
        int deletedParagraphs = 0;
        int headings = 0;
        int[] deletedItems = {0};

        for (JsonNode unit : asList(law.path("조문").get("조문단위"))) {
            if (!"조문".equals(text(unit.get("조문여부")))) {
                headings++;
                continue;
            }
            Integer articleNo = parseIntOrNull(text(unit.get("조문번호")));
            if (articleNo == null) {
                log.warn("조문번호가 없는 조문을 건너뜀: {} {}", name, text(unit.get("조문키")));
                continue;
            }
            int branchNo = parseInt(text(unit.get("조문가지번호")), 0);
            String title = blankToNull(text(unit.get("조문제목")));
            LocalDate from = parseDate(text(unit.get("조문시행일자")));
            if (from == null) {
                from = effectiveDate;
            }
            String articleText = joinText(unit.get("조문내용"));
            List<JsonNode> paragraphs = asList(unit.get("항"));

            if (paragraphs.isEmpty()) {
                if (DELETED_ARTICLE.matcher(articleText).matches()) {
                    deletedArticles++;
                    continue;
                }
                add(articles, keys, new ParsedArticle(articleNo, branchNo, null, title, articleText, from), name);
                continue;
            }

            boolean numbered = paragraphs.stream().anyMatch(p -> blankToNull(text(p.get("항번호"))) != null);
            if (!numbered) {
                // 항번호 없는 본문 + 각 호: 조문 내용 뒤에 호를 이어 붙여 한 행
                List<String> lines = new ArrayList<>();
                addLine(lines, articleText);
                for (JsonNode p : paragraphs) {
                    addLine(lines, joinText(p.get("항내용")));
                    appendItems(lines, p, deletedItems);
                }
                add(articles, keys, new ParsedArticle(articleNo, branchNo, null, title, String.join("\n", lines), from), name);
                continue;
            }

            int index = 0;
            for (JsonNode p : paragraphs) {
                index++;
                String paragraphText = joinText(p.get("항내용"));
                List<JsonNode> items = asList(p.get("호"));
                if (items.isEmpty() && DELETED_PARAGRAPH.matcher(paragraphText).matches()) {
                    deletedParagraphs++;
                    continue;
                }
                Integer paragraphNo = parseParagraphNo(text(p.get("항번호")));
                if (paragraphNo == null) {
                    paragraphNo = index;
                }
                List<String> lines = new ArrayList<>();
                addLine(lines, paragraphText);
                appendItems(lines, p, deletedItems);
                if (lines.isEmpty()) {
                    continue;
                }
                add(articles, keys, new ParsedArticle(articleNo, branchNo, paragraphNo, title, String.join("\n", lines), from), name);
            }
        }

        return new ParsedLaw(
                text(info.get("법령ID")),
                name,
                text(info.path("법종구분").isObject() ? info.path("법종구분").get("content") : info.get("법종구분")),
                blankToNull(text(info.get("법령명약칭"))),
                parseDate(text(info.get("공포일자"))),
                effectiveDate,
                List.copyOf(articles),
                deletedArticles,
                deletedParagraphs,
                deletedItems[0],
                headings);
    }

    /** "①" → 1, "⑳" → 20, "㉑" → 21, "㊿" → 50, "3" / "제3항" → 3. 모르면 null. */
    static Integer parseParagraphNo(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int cp = s.codePointAt(0);
        if (cp >= 0x2460 && cp <= 0x2473) {
            return cp - 0x2460 + 1;
        }
        if (cp >= 0x3251 && cp <= 0x325F) {
            return cp - 0x3251 + 21;
        }
        if (cp >= 0x32B1 && cp <= 0x32BF) {
            return cp - 0x32B1 + 36;
        }
        Matcher m = DIGITS.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** 호·목을 줄로 붙인다. "3. 삭제 <...>" 처럼 삭제 표시만 있는 호·목은 버리고 deleted[0] 에 센다. */
    private static void appendItems(List<String> lines, JsonNode paragraph, int[] deleted) {
        for (JsonNode item : asList(paragraph.get("호"))) {
            String itemText = joinText(item.get("호내용"));
            List<JsonNode> subs = asList(item.get("목"));
            if (subs.isEmpty() && DELETED_ITEM.matcher(itemText).matches()) {
                deleted[0]++;
                continue;
            }
            addLine(lines, itemText);
            for (JsonNode sub : subs) {
                String subText = joinText(sub.get("목내용"));
                if (DELETED_ITEM.matcher(subText).matches()) {
                    deleted[0]++;
                    continue;
                }
                addLine(lines, subText);
            }
        }
    }

    private static void add(List<ParsedArticle> out, Set<String> keys, ParsedArticle a, String lawName) {
        String key = a.articleNo() + "-" + a.branchNo() + "-" + (a.paragraphNo() == null ? 0 : a.paragraphNo()) + "-" + a.effectiveFrom();
        if (!keys.add(key)) {
            log.warn("같은 조문·항이 두 번 나와 뒤의 것을 버림: {} {}", lawName, key);
            return;
        }
        out.add(a);
    }

    private static void addLine(List<String> lines, String text) {
        if (text != null && !text.isBlank()) {
            lines.add(text);
        }
    }

    /** 문자열, 문자열 배열, 중첩 배열 모두 줄 단위로 펴서 잇는다. 각 줄 앞뒤 공백은 지운다. */
    static String joinText(JsonNode node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return String.join("\n", out);
    }

    private static void collect(JsonNode node, List<String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> collect(n, out));
            return;
        }
        if (node.isObject()) {
            JsonNode content = node.get("content");
            if (content != null) {
                collect(content, out);
            }
            return;
        }
        for (String line : node.asText().split("\\R")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
    }

    private static List<JsonNode> asList(JsonNode node) {
        List<JsonNode> list = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return list;
        }
        if (node.isArray()) {
            node.forEach(list::add);
        } else if (node.isObject()) {
            list.add(node);
        }
        return list;
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 이 아닙니다.", e);
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isContainerNode()) {
            return null;
        }
        return node.asText().strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static LocalDate parseDate(String yyyymmdd) {
        if (yyyymmdd == null || !yyyymmdd.matches("\\d{8}")) {
            return null;
        }
        return LocalDate.parse(yyyymmdd, YMD);
    }

    private static int parseInt(String s, int fallback) {
        Integer v = parseIntOrNull(s);
        return v == null ? fallback : v;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || !s.matches("\\d+")) {
            return null;
        }
        return Integer.parseInt(s);
    }
}
