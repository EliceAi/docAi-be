package com.team.docai.domain.law.service;

import com.team.docai.global.config.LawProperties;
import com.team.docai.domain.law.dto.ArticleNumber;
import com.team.docai.domain.law.dto.GuidanceInfo;
import com.team.docai.domain.law.dto.IssueTagInfo;
import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.entity.Law;
import com.team.docai.domain.law.entity.LawAlias;
import com.team.docai.domain.law.entity.LawArticle;
import com.team.docai.domain.law.entity.LawNames;
import com.team.docai.domain.law.repository.DocumentTypeLawRepository;
import com.team.docai.domain.law.repository.GuidanceRepository;
import com.team.docai.domain.law.repository.IssueTagArticleRepository;
import com.team.docai.domain.law.repository.IssueTagRepository;
import com.team.docai.domain.law.repository.LawAliasRepository;
import com.team.docai.domain.law.repository.LawArticleRepository;
import com.team.docai.domain.law.repository.LawRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 조문 찾기 (분석 6단계 근거 찾기, 7-1 추가 조회, 챗봇이 쓴다). 읽기만 한다.
 * <ul>
 *   <li>법령 이름 맞추기: 정규화 이름 → 약칭 (DB_AI_흐름 ①②). 추정(③)은 하지 않는다.</li>
 *   <li>조문 번호로 직접 찾기: "제10조의2" → (10, 2).</li>
 *   <li>키워드 전문 검색: search_vector(simple) 에 키워드 앞부분 일치(키워드:*)로 찾고 ts_rank 순.</li>
 *   <li>꼬리표 → 조문 매핑, 해석 메모, 문서 종류별 관련 법령.</li>
 *   <li>일상용어 → 법령용어 사전으로 넓힌 검색 ({@link #searchWithDailyTerms}).</li>
 * </ul>
 * 기준일(계약일)에 유효한 행만 돌려준다. 기준일이 null 이면 오늘.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LawSearchService {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final List<Long> NO_LAW_FILTER = List.of(-1L);

    private final LawRepository lawRepository;
    private final LawAliasRepository lawAliasRepository;
    private final LawArticleRepository lawArticleRepository;
    private final DocumentTypeLawRepository documentTypeLawRepository;
    private final IssueTagArticleRepository issueTagArticleRepository;
    private final GuidanceRepository guidanceRepository;
    private final IssueTagRepository issueTagRepository;
    private final LawProperties properties;
    private final LawTermDictionary lawTermDictionary;

    /** 문서 종류의 문제 꼬리표 (분석 1차 프롬프트, 저장 전 목록 확인). 코드 순 */
    public List<IssueTagInfo> findIssueTags(String documentTypeCode) {
        return issueTagRepository.findByDocumentTypeCodeOrderByCodeAsc(documentTypeCode).stream()
                .map(t -> new IssueTagInfo(t.getCode(), t.getName()))
                .toList();
    }

    /** 법령 이름(정식 명칭·띄어쓰기 다름·약칭)으로 법령 ID. 못 찾으면 empty. */
    public Optional<Long> resolveLawId(String name) {
        String norm = LawNames.normalize(name);
        if (norm == null || norm.isEmpty()) {
            return Optional.empty();
        }
        return lawRepository.findByNameNorm(norm).map(Law::getLawId)
                .or(() -> lawAliasRepository.findById(norm).map(LawAlias::getLawId));
    }

    /**
     * 법령 이름 + 조문 번호로 직접 찾기 (AI 법령 힌트 확인). 항을 적었으면 그 항만, 아니면 그 조의 모든 항.
     * 법령이나 조문이 DB 에 없으면 빈 목록.
     */
    public List<LawArticleInfo> findArticles(String lawName, String article, LocalDate baseDate) {
        Optional<Long> lawId = resolveLawId(lawName);
        Optional<ArticleNumber> number = ArticleNumber.parse(article);
        if (lawId.isEmpty() || number.isEmpty()) {
            return List.of();
        }
        return findArticles(lawId.get(), number.get(), baseDate);
    }

    /** 항을 물었는데 그 조문이 항 없이 한 행(paragraph_no = null)뿐이면 조문 전체를 돌려준다. */
    public List<LawArticleInfo> findArticles(Long lawId, ArticleNumber number, LocalDate baseDate) {
        LocalDate date = dateOrToday(baseDate);
        List<Long> ids = lawArticleRepository.findArticleIds(lawId, number.articleNo(), number.branchNo(), date);
        List<LawArticleInfo> rows = load(ids, Map.of(), date);
        if (number.paragraphNo() == null) {
            return rows;
        }
        if (!rows.isEmpty() && rows.stream().allMatch(r -> r.paragraphNo() == null)) {
            return rows;
        }
        return rows.stream().filter(r -> number.paragraphNo().equals(r.paragraphNo())).toList();
    }

    /**
     * 키워드 전문 검색.
     *
     * @param keywords 검색어 (예: ["위약금", "교육비"]). 하나라도 맞으면 후보, 많이 맞을수록 점수가 높다
     * @param lawIds   검색할 법령 (문서 종류별 관련 법령). 비거나 null 이면 모든 법령
     * @param limit    결과 수. null 이면 search-default-limit, search-max-limit 를 넘지 않음
     */
    public List<LawArticleInfo> search(List<String> keywords, Collection<Long> lawIds, LocalDate baseDate, Integer limit) {
        String tsquery = toTsQuery(keywords, properties.searchMaxKeywords(), properties.searchMaxQueryLength());
        if (tsquery.isEmpty()) {
            return List.of();
        }
        int size = Math.min(limit == null || limit < 1 ? properties.searchDefaultLimit() : limit, properties.searchMaxLimit());
        boolean all = lawIds == null || lawIds.isEmpty();
        LocalDate date = dateOrToday(baseDate);
        List<Object[]> hits = lawArticleRepository.searchIds(tsquery, all, all ? NO_LAW_FILTER : lawIds,
                date, properties.searchMinScore(), size);
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (Object[] row : hits) {
            scores.put(((Number) row[0]).longValue(), ((Number) row[1]).doubleValue());
        }
        return load(new ArrayList<>(scores.keySet()), scores, date);
    }

    /**
     * 지금 시행 중인 조문 중 검색어 하나에 맞는 행 수 (검색과 같은 앞부분 일치). 일상용어 사전 적재가 쓴다.
     * 검색어가 글자·숫자를 하나도 갖지 않거나 법령이 없으면 0.
     */
    public long countCurrentArticles(String keyword, Collection<Long> lawIds) {
        String tsquery = toTsQuery(List.of(keyword), 1, properties.searchMaxQueryLength());
        if (tsquery.isEmpty() || lawIds == null || lawIds.isEmpty()) {
            return 0;
        }
        return lawArticleRepository.countCurrentMatching(tsquery, lawIds);
    }

    /**
     * 일상용어를 법령용어로 넓힌 검색 (근거 찾기 전문 검색, 7-1 추가 조회, 챗봇 조문 검색).
     * <ol>
     *   <li>원래 검색어로 {@link #search} 한다. 결과·순서·상한은 넓히기 전과 같다.</li>
     *   <li>칸이 남고 사전({@link LawTermDictionary})에 맞는 일상용어가 있으면, 법령용어 + 원래 검색어로 한 번 더 검색해
     *       아직 없는 조문만 점수 순으로 뒤에 채운다. 상한(limit)은 그대로.</li>
     * </ol>
     * 더한 법령용어가 원래 검색어의 결과를 밀어내지 않게 두 번 나눠 찾는다 (흔한 법령용어를 OR 로 섞으면 점수가 흐려져
     * "위약금" 같은 정확한 조문이 상한 밖으로 밀리는 것을 실제 DB 로 확인함, 2026-11-19).
     */
    public List<LawArticleInfo> searchWithDailyTerms(List<String> keywords, Collection<Long> lawIds, LocalDate baseDate,
                                                     Integer limit) {
        List<LawArticleInfo> first = search(keywords, lawIds, baseDate, limit);
        int size = Math.min(limit == null || limit < 1 ? properties.searchDefaultLimit() : limit, properties.searchMaxLimit());
        if (first.size() >= size || keywords == null) {
            return first;
        }
        List<String> added = lawTermDictionary.added(keywords);
        if (added.isEmpty()) {
            return first;
        }
        // 법령용어를 앞에 둔다: 검색어 수 상한(search-max-keywords)에 걸려도 더한 말이 잘리지 않게. 원래 검색어 결과는 1번에서 이미 앞에 있다
        List<String> expanded = new ArrayList<>(added);
        keywords.stream().filter(k -> k != null).forEach(expanded::add);
        Set<Long> seen = first.stream().map(LawArticleInfo::lawArticleId).collect(Collectors.toSet());
        List<LawArticleInfo> out = new ArrayList<>(first);
        for (LawArticleInfo a : search(expanded, lawIds, baseDate, Math.min(size + first.size(), properties.searchMaxLimit()))) {
            if (out.size() >= size) {
                break;
            }
            if (seen.add(a.lawArticleId())) {
                out.add(a);
            }
        }
        return List.copyOf(out);
    }

    /** 문서 종류의 관련 법령 ID (핵심 법령 먼저). document_type_law 가 비어 있으면 빈 목록. */
    public List<Long> findLawIds(String documentTypeCode) {
        return documentTypeLawRepository.findByDocumentTypeCodeOrderByCoreDescLawIdAsc(documentTypeCode).stream()
                .map(d -> d.getLawId())
                .toList();
    }

    /** 꼬리표에 매핑된 조문 (우선순위 순). */
    public List<LawArticleInfo> findByIssueTag(String issueTagCode) {
        List<Long> ids = issueTagArticleRepository.findByIssueTagCodeOrderByPriorityAsc(issueTagCode).stream()
                .map(m -> m.getLawArticleId())
                .toList();
        return load(ids, Map.of(), null);
    }

    public List<GuidanceInfo> findGuidance(String issueTagCode) {
        return guidanceRepository.findByIssueTagCodeOrderByGuidanceIdAsc(issueTagCode).stream()
                .map(GuidanceInfo::from)
                .toList();
    }

    public List<LawArticleInfo> getArticles(Collection<Long> lawArticleIds) {
        return load(new ArrayList<>(new LinkedHashSet<>(lawArticleIds)), Map.of(), null);
    }

    /**
     * 키워드 → tsquery. 키워드 안의 낱말은 AND, 키워드끼리는 OR, 낱말마다 앞부분 일치(:*).
     * 한국어 조사가 붙은 낱말("위약금을")도 "위약금:*" 으로 맞는다. 글자·숫자만 남기므로 tsquery 문법 오류가 나지 않는다.
     * 앞에서부터 키워드 maxKeywords 개까지, 만든 글이 maxLength 자를 넘기 전까지만 쓴다.
     */
    static String toTsQuery(List<String> keywords, int maxKeywords, int maxLength) {
        if (keywords == null) {
            return "";
        }
        Set<String> parts = new LinkedHashSet<>();
        int length = 0;
        for (String keyword : keywords) {
            if (parts.size() >= maxKeywords) {
                break;
            }
            if (keyword == null) {
                continue;
            }
            List<String> tokens = TOKEN_SPLIT.splitAsStream(keyword.toLowerCase())
                    .filter(t -> !t.isEmpty())
                    .map(t -> t + ":*")
                    .toList();
            if (tokens.isEmpty()) {
                continue;
            }
            String part = tokens.size() == 1 ? tokens.get(0) : "(" + String.join(" & ", tokens) + ")";
            int added = part.length() + (parts.isEmpty() ? 0 : 3);
            if (length + added > maxLength) {
                break;
            }
            if (parts.add(part)) {
                length += added;
            }
        }
        return String.join(" | ", parts);
    }

    private List<LawArticleInfo> load(List<Long> ids, Map<Long, Double> scores, LocalDate baseDate) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, LawArticle> byId = lawArticleRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(LawArticle::getLawArticleId, Function.identity()));
        Set<Long> lawIds = byId.values().stream().map(LawArticle::getLawId).collect(Collectors.toSet());
        Map<Long, String> names = lawRepository.findAllById(lawIds).stream()
                .collect(Collectors.toMap(Law::getLawId, Law::getName));
        List<LawArticleInfo> out = new ArrayList<>();
        for (Long id : ids) {
            LawArticle a = byId.get(id);
            if (a != null) {
                out.add(LawArticleInfo.of(a, names.get(a.getLawId()), scores.get(id), baseDate));
            }
        }
        return out;
    }

    private static LocalDate dateOrToday(LocalDate baseDate) {
        return baseDate != null ? baseDate : LocalDate.now();
    }
}
