package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.RefFoundBy;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.service.rule.EvidenceCollector.ExistingRef;
import com.team.docai.domain.analysis.service.rule.EvidenceCollector.RefDraft;
import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.service.LawSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 7-1 추가 조회: 2차가 needMore 로 요청한 조항에 6단계와 같은 방법({@link EvidenceCollector})으로 조문을 더 찾아 붙인다.
 * <ul>
 *   <li>검색어(searchKeywords, 그다음 want)로 문서 종류별 관련 법령 안에서 전문 검색. 기준일은 6단계와 같다.</li>
 *   <li>이미 붙은 근거와 같은 조문은 건너뛴다. 새 ID 는 기존 가장 큰 번호 다음 (L7, L8 ...). found_by = NEED_MORE, cited = false.</li>
 *   <li>새로 붙이는 수는 조항당 {@link #MAX_NEW_REFS}개까지 (2차에 다시 보내는 글이 너무 길어지지 않게).</li>
 * </ul>
 * 찾기는 트랜잭션 밖, 저장은 {@link RuleWriter#saveRefs} 의 짧은 트랜잭션.
 */
@Component
@RequiredArgsConstructor
public class NeedMoreEvidence {

    static final int MAX_NEW_REFS = 3;

    private final AnalysisClauseRefRepository refRepository;
    private final LawSearchService lawSearchService;
    private final RuleWriter ruleWriter;

    /**
     * @param lawIds 문서 종류별 관련 법령 (LawSearchService.findLawIds)
     * @return 새로 붙인 근거 수
     */
    public int collect(Analysis analysis, Long clauseId, List<String> keywords, String want, List<Long> lawIds) {
        List<String> query = new ArrayList<>(keywords == null ? List.of() : keywords);
        if (want != null && !want.isBlank()) {
            query.add(want);
        }
        if (query.isEmpty()) {
            return 0;
        }
        LocalDate baseDate = RuleValues.baseDate(analysis);
        List<ExistingRef> existing = refRepository.findByClauseIdOrderByRefIdAsc(clauseId).stream()
                .map(ExistingRef::from).toList();
        EvidenceCollector collector = new EvidenceCollector(clauseId, existing, List.of(), baseDate,
                existing.size() + MAX_NEW_REFS);
        // 이미 붙은 조문이 섞여 나올 수 있어 넉넉히 받아 앞에서부터 채운다
        List<LawArticleInfo> found = lawSearchService.search(query, lawIds, baseDate, existing.size() + MAX_NEW_REFS);
        collector.addLaws(RefFoundBy.NEED_MORE, found);
        List<RefDraft> drafts = collector.drafts();
        if (!drafts.isEmpty()) {
            ruleWriter.saveRefs(drafts);
        }
        return drafts.size();
    }
}
