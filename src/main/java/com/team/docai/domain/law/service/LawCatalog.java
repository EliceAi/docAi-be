package com.team.docai.domain.law.service;

import com.team.docai.domain.law.dto.RuleSeed;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 적재 대상 법령과 팀이 정한 시드 (약칭, 상위 법령, 숫자 규칙).
 * 법령 수가 적고 코드 리뷰로 바꾸는 값이라 yml 이 아닌 코드에 둔다.
 */
public final class LawCatalog {

    private LawCatalog() {
    }

    /**
     * @param name       법령명한글 (목록에서 정확히 같은 이름을 고른다)
     * @param listQuery  목록 조회 검색어. 같은 검색어는 한 번만 부른다 (근로기준법 검색에 시행령도 나옴)
     * @param parentName 상위 법령 (시행령 → 법률). 없으면 null. API 에 연결 필드가 없어 시드로 둔다 (설계_점검 35번)
     */
    public record Target(String name, String listQuery, String parentName) {
    }

    /**
     * 민법은 전체를 저장한다. 문서(ERD·아키텍처·설계_점검)에 저장 범위가 정해져 있지 않고,
     * 근거 찾기는 문서 종류별 관련 법령 안에서 검색 점수로 고르므로 범위를 자를 이유가 없다.
     * 계약 일반(제103조 반사회질서, 제398조 손해배상액 예정, 제428조의2 보증 방식 등)도 임대차·근로 계약 판단에 쓰인다.
     */
    public static final List<Target> TARGETS = List.of(
            new Target("근로기준법", "근로기준법", null),
            new Target("근로기준법 시행령", "근로기준법", "근로기준법"),
            new Target("최저임금법", "최저임금법", null),
            new Target("근로자퇴직급여 보장법", "근로자퇴직급여 보장법", null),
            new Target("주택임대차보호법", "주택임대차보호법", null),
            new Target("주택임대차보호법 시행령", "주택임대차보호법", "주택임대차보호법"),
            new Target("민법", "민법", null));

    /** 사람이 붙인 줄임말 (law_alias.source = MANUAL). 키: 줄임말, 값: 법령명. API 법령약칭명은 따로 넣는다. */
    public static final Map<String, String> MANUAL_ALIASES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("근기법", "근로기준법");
        m.put("최임법", "최저임금법");
        m.put("퇴직급여법", "근로자퇴직급여 보장법");
        m.put("주임법", "주택임대차보호법");
        MANUAL_ALIASES = Map.copyOf(m);
    }

    /**
     * @param core 핵심 법령 여부 (검색 범위·우선순위). 그 문서의 직접 근거 법률은 true, 시행령·일반법(민법)은 false
     */
    public record DocumentTypeLawSeed(String documentTypeCode, String lawName, boolean core) {
    }

    /** 문서 종류별 관련 법령 (2026-11-17 팀 지시). document_type 은 B 소유라 행이 없으면 그 종류만 건너뛴다. */
    public static final List<DocumentTypeLawSeed> DOCUMENT_TYPE_LAWS = List.of(
            new DocumentTypeLawSeed("EMPLOYMENT", "근로기준법", true),
            new DocumentTypeLawSeed("EMPLOYMENT", "근로기준법 시행령", false),
            new DocumentTypeLawSeed("EMPLOYMENT", "최저임금법", true),
            new DocumentTypeLawSeed("EMPLOYMENT", "근로자퇴직급여 보장법", true),
            new DocumentTypeLawSeed("LEASE", "주택임대차보호법", true),
            new DocumentTypeLawSeed("LEASE", "주택임대차보호법 시행령", false),
            new DocumentTypeLawSeed("LEASE", "민법", false));

    /**
     * 숫자 규칙 기준값. 적재 마지막 단계에서 넣는다.
     * MIN_WAGE 근거는 최저임금법 제6조제1항 (최저임금액 이상 지급 의무, 아키텍처 5단계 예시 "최저임금법 제6조").
     */
    public static final List<RuleSeed> RULE_SEEDS = List.of(
            new RuleSeed("MIN_WAGE", LocalDate.of(2026, 1, 1), Map.of("hourly", 10320),
                    "최저임금법", 6, 0, 1, "2026년 적용 최저임금 시급 10,320원 (고용노동부 고시)"));
}
