package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.RefFoundBy;
import com.team.docai.domain.law.dto.GuidanceInfo;
import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.global.util.TextTruncator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 조항 하나의 근거 모으기 (아키텍처 6단계). DB 를 읽지 않고, 찾아 온 후보를 순서대로 채워 저장할 근거(RefDraft)를 만든다.
 * <ol>
 *   <li>꼬리표 → 조문 매핑 (TAG_MAP), 2. AI 법령 힌트 중 DB 에 있는 조문 (AI_HINT), 3. 전문 검색 (SEARCH),
 *       4. 해석 메모 (조항당 1개)</li>
 *   <li>조항당 최대 max 개. 이미 붙은 근거(5단계 RULE)도 센다. 해석 메모가 있으면 1칸을 남겨 둔다</li>
 *   <li>같은 조문·같은 메모는 한 번만 (이미 붙은 근거와도 비교)</li>
 *   <li>ID: 조문 L, 메모 G 에 조항 안에서 이어지는 번호 (L1, L2, G3). 이미 붙은 근거의 가장 큰 번호 다음부터</li>
 *   <li>cited = false (7단계 2차가 인용 여부를 정하고 8단계 검증이 바꾼다)</li>
 * </ol>
 */
final class EvidenceCollector {

    static final String FALLBACK_NOTE = "[현행 조문으로 대신함: 계약일 당시 조문과 다를 수 있음]\n";
    private static final Pattern LABEL_NUMBER = Pattern.compile("^[A-Za-z]+(\\d+)$");

    /** 조항에 이미 붙은 근거. */
    record ExistingRef(String refLabel, Long lawArticleId, Long guidanceId) {
        static ExistingRef from(AnalysisClauseRef r) {
            return new ExistingRef(r.getRefLabel(), r.getLawArticleId(), r.getGuidanceId());
        }
    }

    /** 저장할 근거 한 행. */
    record RefDraft(Long clauseId, String refLabel, Long lawArticleId, Long guidanceId, RefFoundBy foundBy,
                    boolean cited, String refTitle, String refText) {
    }

    private record LawPick(RefFoundBy foundBy, LawArticleInfo article) {
    }

    private final Long clauseId;
    private final LocalDate baseDate;
    private final int nextNumberStart;
    private final int lawRoom;
    private final Set<Long> usedLaws = new HashSet<>();
    private final List<LawPick> laws = new ArrayList<>();
    private final GuidanceInfo guidance;

    /**
     * @param guidanceCandidates 이 조항 꼬리표의 해석 메모 (없으면 빈 목록). 이미 붙은 메모가 아닌 첫 번째 것 하나만 쓴다
     */
    EvidenceCollector(Long clauseId, List<ExistingRef> existing, List<GuidanceInfo> guidanceCandidates,
                      LocalDate baseDate, int max) {
        this.clauseId = clauseId;
        this.baseDate = baseDate;
        this.nextNumberStart = nextNumber(existing);
        Set<Long> usedGuidance = new HashSet<>();
        for (ExistingRef r : existing) {
            if (r.lawArticleId() != null) {
                usedLaws.add(r.lawArticleId());
            }
            if (r.guidanceId() != null) {
                usedGuidance.add(r.guidanceId());
            }
        }
        int room = Math.max(0, max - existing.size());
        GuidanceInfo g = guidanceCandidates.stream()
                .filter(c -> !usedGuidance.contains(c.guidanceId()))
                .findFirst().orElse(null);
        this.guidance = room > 0 && usedGuidance.isEmpty() ? g : null;
        this.lawRoom = room - (this.guidance != null ? 1 : 0);
    }

    /** 순서대로 넣는다. 이미 있는 조문은 건너뛰고, 칸이 차면 나머지는 버린다. */
    EvidenceCollector addLaws(RefFoundBy foundBy, List<LawArticleInfo> articles) {
        for (LawArticleInfo a : articles) {
            if (!wantsMoreLaws()) {
                break;
            }
            if (a != null && usedLaws.add(a.lawArticleId())) {
                laws.add(new LawPick(foundBy, a));
            }
        }
        return this;
    }

    /** 조문 칸이 남았는지. 1·2번(꼬리표·힌트)으로 다 차면 3번 검색을 건너뛴다. */
    boolean wantsMoreLaws() {
        return laws.size() < lawRoom;
    }

    List<RefDraft> drafts() {
        List<RefDraft> out = new ArrayList<>();
        int n = nextNumberStart;
        for (LawPick p : laws) {
            out.add(lawRef(clauseId, "L" + n++, p.foundBy(), false, p.article(), baseDate));
        }
        if (guidance != null) {
            out.add(new RefDraft(clauseId, "G" + n, null, guidance.guidanceId(), RefFoundBy.TAG_MAP, false,
                    TextTruncator.truncate(guidance.title(), AnalysisClauseRef.REF_TITLE_MAX_LENGTH),
                    guidance.content()));
        }
        return out;
    }

    /** 조문 근거 한 행. 기준일에 시행 중인 조문이 없어 현행 조문으로 대신했으면 ref_text 앞에 표시한다. */
    static RefDraft lawRef(Long clauseId, String label, RefFoundBy foundBy, boolean cited, LawArticleInfo a,
                           LocalDate baseDate) {
        String title = a.label() + (a.title() == null || a.title().isBlank() ? "" : " (" + a.title() + ")");
        boolean fallback = a.fallbackToCurrent()
                || (baseDate != null && a.effectiveFrom() != null && a.effectiveFrom().isAfter(baseDate));
        return new RefDraft(clauseId, label, a.lawArticleId(), null, foundBy, cited,
                TextTruncator.truncate(title, AnalysisClauseRef.REF_TITLE_MAX_LENGTH),
                fallback ? FALLBACK_NOTE + a.content() : a.content());
    }

    /** 이미 붙은 근거 ID 의 가장 큰 번호 + 1 (없으면 1). */
    static int nextNumber(List<ExistingRef> existing) {
        int max = 0;
        for (ExistingRef r : existing) {
            Matcher m = LABEL_NUMBER.matcher(Objects.toString(r.refLabel(), ""));
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return max + 1;
    }

    /**
     * 꼬리표 매핑은 시행 기간을 보지 않고 ID 로 읽으므로, 기준일 전에 효력이 끝난 행(effective_to &lt; 기준일)은 뺀다.
     * 기준일보다 뒤에 시행된 행은 남기고 {@link #lawRef} 가 대체 표시를 붙인다.
     */
    static List<LawArticleInfo> notEndedBefore(List<LawArticleInfo> articles, LocalDate baseDate) {
        if (baseDate == null) {
            return articles;
        }
        return articles.stream().filter(a -> a.effectiveTo() == null || !a.effectiveTo().isBefore(baseDate)).toList();
    }

    /**
     * 꼬리표 매핑 조문 정렬. 매핑은 조문 단위 우선순위라 같은 순위 안의 항 순서가 정해져 있지 않다.
     * 처음 나온 조문 순서는 지키고, 같은 조문 안에서는 항 번호 순(항 없는 행 먼저)으로 둔다.
     */
    static List<LawArticleInfo> orderTagArticles(List<LawArticleInfo> articles) {
        Map<String, List<LawArticleInfo>> groups = new LinkedHashMap<>();
        for (LawArticleInfo a : articles) {
            groups.computeIfAbsent(a.lawId() + ":" + a.articleNo() + ":" + a.articleBranchNo(), k -> new ArrayList<>())
                    .add(a);
        }
        Comparator<LawArticleInfo> byParagraph = Comparator.comparing(LawArticleInfo::paragraphNo,
                Comparator.nullsFirst(Comparator.naturalOrder()));
        List<LawArticleInfo> out = new ArrayList<>();
        for (List<LawArticleInfo> g : groups.values()) {
            g.sort(byParagraph);
            out.addAll(g);
        }
        return out;
    }
}
