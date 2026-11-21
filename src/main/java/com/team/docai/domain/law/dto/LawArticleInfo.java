package com.team.docai.domain.law.dto;

import com.team.docai.domain.law.entity.LawArticle;

import java.time.LocalDate;

/**
 * 조문 한 행 (항 단위). 근거 찾기(6단계)가 analysis_clause_ref 의 ref_title·ref_text 스냅샷을 만들 때 쓴다.
 *
 * @param score             전문 검색 점수 (ts_rank). 검색이 아닌 조회면 null
 * @param fallbackToCurrent 기준일(계약일)에 시행 중인 행이 DB 에 없어(기준일이 가장 옛 개정본 시행일보다 앞섬)
 *                          가장 가까운 개정본 행으로 대신했는지. 적재기는 법제처 목록의 현행 개정본 하나만 받으므로 그 행은
 *                          보통 현행 행이지만, 현행 개정본이 시행 예정 조문을 담은 판(공포 후 시행 전)이면 시행일이 오늘보다
 *                          뒤인 시행 예정 행일 수 있다 (예: 민간임대주택에 관한 특별법 시행령, 2027-04-28 시행).
 *                          어느 쪽인지는 {@link #substituteNote} 가 시행일과 오늘을 비교해 가린다
 */
public record LawArticleInfo(
        Long lawArticleId,
        Long lawId,
        String lawName,
        int articleNo,
        int articleBranchNo,
        Integer paragraphNo,
        String title,
        String content,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Double score,
        boolean fallbackToCurrent
) {
    /** @param baseDate 기준일. null 이면 대체 판단을 하지 않는다 (ID 로 직접 읽은 경우) */
    public static LawArticleInfo of(LawArticle a, String lawName, Double score, LocalDate baseDate) {
        boolean fallback = baseDate != null && a.getEffectiveFrom().isAfter(baseDate);
        return new LawArticleInfo(a.getLawArticleId(), a.getLawId(), lawName, a.getArticleNo(), a.getArticleBranchNo(),
                a.getParagraphNo(), a.getTitle(), a.getContent(), a.getEffectiveFrom(), a.getEffectiveTo(), score, fallback);
    }

    /** 기준일 당시 조문이 아니라 그 뒤에 시행된 현행 조문으로 대신했을 때 본문 앞에 붙이는 표시. */
    public static final String CURRENT_SUBSTITUTE_NOTE = "[현행 조문으로 대신함: 계약일 당시 조문과 다를 수 있음]\n";

    /** 아직 시행되지 않은 조문으로 대신했을 때 본문 앞에 붙이는 표시. %s 는 시행일 (YYYY-MM-DD). */
    static final String UPCOMING_SUBSTITUTE_NOTE = "[시행 예정 조문(%s 시행)으로 대신함: 지금 적용되는 조문과 다를 수 있음]\n";

    /**
     * 본문 앞에 붙일 대체 표시. 대신 쓴 행이 아니면 빈 글자.
     * <ul>
     *   <li>대신 쓴 행: fallbackToCurrent 이거나 시행일이 기준일(없으면 오늘)보다 뒤</li>
     *   <li>시행일이 오늘보다 뒤면 시행 예정 조문 표시 (시행일 포함), 아니면 현행 조문 표시</li>
     * </ul>
     *
     * @param baseDate 기준일 (계약일 또는 분석일). null 이면 오늘을 기준일로 본다 (챗봇 질문 검색)
     * @param today    오늘 (한국 날짜)
     */
    public String substituteNote(LocalDate baseDate, LocalDate today) {
        if (effectiveFrom == null) {
            return fallbackToCurrent ? CURRENT_SUBSTITUTE_NOTE : "";
        }
        LocalDate base = baseDate != null ? baseDate : today;
        boolean substituted = fallbackToCurrent || (base != null && effectiveFrom.isAfter(base));
        if (!substituted) {
            return "";
        }
        if (today != null && effectiveFrom.isAfter(today)) {
            return UPCOMING_SUBSTITUTE_NOTE.formatted(effectiveFrom);
        }
        return CURRENT_SUBSTITUTE_NOTE;
    }

    /** "근로기준법 제20조", 항이 있으면 "근로기준법 제17조제1항". */
    public String label() {
        return lawName + " " + ArticleNumber.label(articleNo, articleBranchNo, paragraphNo);
    }
}
