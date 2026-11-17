package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.entity.KeyTermState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 빠진 필수 기재사항 (아키텍처 5단계, analysis_finding kind = MISSING_ITEM). 계산만 하고 저장하지 않는다.
 * <ul>
 *   <li>근로계약서: 근로기준법 제17조제1항 (임금, 소정근로시간, 휴일, 연차유급휴가) +
 *       같은 항 제5호의 시행령 제8조 (취업 장소, 종사 업무)</li>
 *   <li>빠짐 = 그 항목의 주요 조건이 state = MISSING 이고, 같은 항목을 가리키는 다른 라벨에 값이 없을 때.
 *       UNREADABLE·MASKED 는 "읽지 못함"이지 누락이 아니다 (아키텍처 5단계). 주요 조건 행 자체가 없으면 판단하지 않는다</li>
 *   <li>임대차계약서는 법이 정한 필수 기재사항 목록이 없어 비워 둔다</li>
 * </ul>
 */
final class RequiredItems {

    /**
     * @param labels  같은 항목으로 보는 주요 조건 라벨 (공백 무시)
     * @param article 근거 조문 (findArticles 로 찾아 첫 행을 law_article_id 로 쓴다)
     */
    record Item(String name, Set<String> labels, String description, String lawName, String article) {
    }

    /** 주요 조건 한 행에서 판단에 쓰는 것. */
    record KeyTermFact(String label, KeyTermState state) {
    }

    static final Map<String, List<Item>> BY_DOCUMENT_TYPE = Map.of(
            "EMPLOYMENT", List.of(
                    new Item("임금", Set.of("임금", "급여"),
                            "임금(구성항목·계산방법·지급방법)을 꼭 적어야 해요", "근로기준법", "제17조제1항"),
                    new Item("근로시간", Set.of("근로시간", "소정근로시간"),
                            "하루·한 주 일하는 시간(소정근로시간)을 꼭 적어야 해요", "근로기준법", "제17조제1항"),
                    new Item("휴일", Set.of("휴일", "주휴일"),
                            "주휴일을 꼭 적어야 해요", "근로기준법", "제17조제1항"),
                    new Item("연차휴가", Set.of("연차휴가", "연차유급휴가", "연차"),
                            "연차유급휴가를 꼭 적어야 해요", "근로기준법", "제17조제1항"),
                    new Item("근무장소", Set.of("근무장소", "취업장소", "근무지"),
                            "일하는 장소를 꼭 적어야 해요", "근로기준법 시행령", "제8조"),
                    new Item("업무", Set.of("업무", "업무내용", "종사업무"),
                            "맡을 업무를 꼭 적어야 해요", "근로기준법 시행령", "제8조")));

    private RequiredItems() {
    }

    static final String WAGE_ONLY_NAME = "근로계약서와 함께 확인";

    /**
     * 임금만 정한 별도 문서(연봉계약서 등, extraction_json.wageOnlyDocument = true)는 휴일·연차 같은 항목을
     * 원래 적지 않으므로 항목마다 "빠짐"을 만들지 않는다. 빠진 것이 있으면 근로계약서와 함께 확인하라는 안내 1건으로 바꾼다.
     */
    static List<Item> findMissing(String documentTypeCode, List<KeyTermFact> keyTerms, boolean wageOnlyDocument) {
        List<Item> missing = findMissing(documentTypeCode, keyTerms);
        if (!wageOnlyDocument || missing.isEmpty()) {
            return missing;
        }
        String names = String.join("·", missing.stream().map(Item::name).toList());
        return List.of(new Item(WAGE_ONLY_NAME, Set.of(),
                "임금만 정한 문서라 " + names + "은(는) 이 문서에 없어요. 근로계약서에 적혀 있는지 함께 확인해 주세요",
                "근로기준법", "제17조제1항"));
    }

    static List<Item> findMissing(String documentTypeCode, List<KeyTermFact> keyTerms) {
        List<Item> items = BY_DOCUMENT_TYPE.getOrDefault(documentTypeCode, List.of());
        List<Item> missing = new ArrayList<>();
        for (Item item : items) {
            Set<String> labels = item.labels().stream().map(RuleValues::normalizeLabel).collect(Collectors.toSet());
            List<KeyTermState> states = keyTerms.stream()
                    .filter(k -> labels.contains(RuleValues.normalizeLabel(k.label())))
                    .map(KeyTermFact::state)
                    .toList();
            if (states.contains(KeyTermState.MISSING) && !states.contains(KeyTermState.OK)
                    && !states.contains(KeyTermState.NEEDS_CHECK)) {
                missing.add(item);
            }
        }
        return missing;
    }

    /** 이 문서 종류에서 판단할 항목 중 주요 조건 행이 하나도 없는 것 (라벨이 달라 못 맞춘 것일 수 있어 로그로만 남긴다). */
    static List<String> unmatched(String documentTypeCode, List<KeyTermFact> keyTerms) {
        Set<String> present = keyTerms.stream().map(k -> RuleValues.normalizeLabel(k.label())).collect(Collectors.toSet());
        return BY_DOCUMENT_TYPE.getOrDefault(documentTypeCode, List.of()).stream()
                .filter(i -> i.labels().stream().map(RuleValues::normalizeLabel).noneMatch(present::contains))
                .map(Item::name)
                .toList();
    }
}
