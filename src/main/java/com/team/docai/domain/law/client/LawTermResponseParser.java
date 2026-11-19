package com.team.docai.domain.law.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 일상용어-법령용어 연계(target=dlytrmRlt, type=JSON) 응답 파서 (2026-11-19 실제 응답으로 확인한 모양).
 * <ul>
 *   <li>연계가 있으면 {@code dlytrmRltService.일상용어.연계용어}. 일상용어·연계용어 모두 1건이면 객체, 여러 건이면 배열.</li>
 *   <li>일치하는 일상용어가 없으면 {@code {"Law": "일치하는 일상용어가 없습니다. ..."}} → 빈 목록.</li>
 *   <li>응답의 링크(용어간관계링크·조문간관계링크)는 쓰지 않는다 (OC 가 들어 있는 주소라 사전에도 남기지 않음).</li>
 * </ul>
 * 같은 법령용어가 여러 번 오면 처음 것만 남긴다.
 */
@Component
@RequiredArgsConstructor
public class LawTermResponseParser {

    private final ObjectMapper objectMapper;

    public List<DailyTermLink> parse(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("일상용어 연계 응답이 JSON 이 아닙니다.");
        }
        if (root == null) {
            throw new IllegalArgumentException("일상용어 연계 응답이 비어 있습니다.");
        }
        JsonNode service = root.path("dlytrmRltService");
        if (service.isMissingNode()) {
            if (root.has("Law")) {
                return List.of();
            }
            throw new IllegalArgumentException("일상용어 연계 응답이 아닙니다.");
        }
        List<DailyTermLink> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (JsonNode daily : asList(service.get("일상용어"))) {
            for (JsonNode link : asList(daily.get("연계용어"))) {
                String term = text(link.get("법령용어명"));
                if (term == null || seen.contains(term)) {
                    continue;
                }
                seen.add(term);
                out.add(new DailyTermLink(term, text(link.get("용어관계코드")), text(link.get("용어관계")),
                        text(link.get("비고"))));
            }
        }
        return out;
    }

    private static List<JsonNode> asList(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return out;
        }
        if (node.isArray()) {
            node.forEach(out::add);
        } else if (node.isObject()) {
            out.add(node);
        }
        return out;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String s = node.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
