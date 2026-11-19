package com.team.docai.domain.law.repository;

import com.team.docai.domain.law.entity.LawArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface LawArticleRepository extends JpaRepository<LawArticle, Long> {

    /**
     * 기준일에 유효한 행. 그 법령에 기준일 이전 시행 행이 하나도 없으면(기준일이 가장 옛 개정본보다 앞섬)
     * 조문마다 가장 옛(가장 가까운) 개정본 행을 대신 쓴다. 지금은 현행본만 적재하므로(설계_점검 남은 일 14) 그 행은 현행 행이다.
     * 대신 쓴 행인지는 서비스가 effective_from > 기준일 로 판단해 LawArticleInfo.fallbackToCurrent 로 알린다.
     */
    String EFFECTIVE_ON = """
            ((a.effective_from <= :baseDate AND (a.effective_to IS NULL OR a.effective_to >= :baseDate))
              OR (NOT EXISTS (SELECT 1 FROM law_article o WHERE o.law_id = a.law_id AND o.effective_from <= :baseDate)
                  AND a.effective_from = (SELECT min(f.effective_from) FROM law_article f
                                          WHERE f.law_id = a.law_id AND f.article_no = a.article_no
                                            AND f.article_branch_no = a.article_branch_no)))
            """;

    List<LawArticle> findByLawIdAndEffectiveFrom(Long lawId, LocalDate effectiveFrom);

    boolean existsByLawIdAndSourceMst(Long lawId, String sourceMst);

    long countByLawIdAndSourceMst(Long lawId, String sourceMst);

    long countByLawIdAndEffectiveToIsNull(Long lawId);

    /** 새 개정본을 저장한 뒤, 다른 개정본에서 온 열린 행을 새 시행일 전날로 닫는다 (ERD 법령 동기화). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update LawArticle a set a.effectiveTo = :effectiveTo
            where a.lawId = :lawId and a.sourceMst <> :sourceMst and a.effectiveTo is null and a.effectiveFrom <= :effectiveTo
            """)
    int closeOtherVersions(@Param("lawId") Long lawId, @Param("sourceMst") String sourceMst,
                           @Param("effectiveTo") LocalDate effectiveTo);

    /** 키워드 전문 검색. 반환 [law_article_id, score]. tsquery 는 서비스가 글자·숫자만으로 만든다. */
    @Query(value = """
            SELECT a.law_article_id, ts_rank(a.search_vector, to_tsquery('simple', :tsquery)) AS score
            FROM law_article a
            WHERE a.search_vector @@ to_tsquery('simple', :tsquery)
              AND (:allLaws = true OR a.law_id IN (:lawIds))
              AND ts_rank(a.search_vector, to_tsquery('simple', :tsquery)) >= :minScore
              AND """ + EFFECTIVE_ON + """
            ORDER BY score DESC, a.law_id, a.article_no, a.article_branch_no, a.paragraph_no NULLS FIRST
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchIds(@Param("tsquery") String tsquery,
                             @Param("allLaws") boolean allLaws,
                             @Param("lawIds") Collection<Long> lawIds,
                             @Param("baseDate") LocalDate baseDate,
                             @Param("minScore") double minScore,
                             @Param("limit") int limit);

    /** 지금 시행 중인(effective_to 없음) 행 중 tsquery 에 맞는 행 수 (일상용어 사전을 만들 때 법령용어가 DB 에 있는지 본다). */
    @Query(value = """
            SELECT count(*) FROM law_article a
            WHERE a.search_vector @@ to_tsquery('simple', :tsquery)
              AND a.effective_to IS NULL
              AND a.law_id IN (:lawIds)
            """, nativeQuery = true)
    long countCurrentMatching(@Param("tsquery") String tsquery, @Param("lawIds") Collection<Long> lawIds);

    /** 조문 번호로 직접 찾기 (기준일에 유효한 행, 항 순서). */
    @Query(value = """
            SELECT a.law_article_id
            FROM law_article a
            WHERE a.law_id = :lawId AND a.article_no = :articleNo AND a.article_branch_no = :branchNo
              AND """ + EFFECTIVE_ON + """
            ORDER BY a.paragraph_no NULLS FIRST
            """, nativeQuery = true)
    List<Long> findArticleIds(@Param("lawId") Long lawId,
                              @Param("articleNo") int articleNo,
                              @Param("branchNo") int branchNo,
                              @Param("baseDate") LocalDate baseDate);
}
