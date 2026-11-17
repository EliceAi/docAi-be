package com.team.docai.domain.law.service;

import com.team.docai.domain.law.client.LawListItem;
import com.team.docai.domain.law.client.ParsedArticle;
import com.team.docai.domain.law.client.ParsedLaw;
import com.team.docai.domain.law.dto.RuleSeed;
import com.team.docai.domain.law.entity.AliasSource;
import com.team.docai.domain.law.entity.DocumentTypeLaw;
import com.team.docai.domain.law.entity.DocumentTypeLawId;
import com.team.docai.domain.law.entity.Law;
import com.team.docai.domain.law.entity.LawAlias;
import com.team.docai.domain.law.entity.LawArticle;
import com.team.docai.domain.law.entity.LawNames;
import com.team.docai.domain.law.entity.RuleParam;
import com.team.docai.domain.law.entity.RuleParamId;
import com.team.docai.domain.law.repository.DocumentTypeLawRepository;
import com.team.docai.domain.law.repository.LawAliasRepository;
import com.team.docai.domain.law.repository.LawArticleRepository;
import com.team.docai.domain.law.repository.LawRepository;
import com.team.docai.domain.law.repository.RuleParamRepository;
import com.team.docai.global.util.TextTruncator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 법령 적재 저장. 법령 하나(개정본 하나)를 한 트랜잭션으로 저장해, 중간에 끊겨도 반쯤 저장된 개정본이 남지 않는다.
 * API 호출은 여기서 하지 않는다 (LawLoader 가 트랜잭션 밖에서 부른다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LawWriter {

    static final int TITLE_MAX = 100;

    private final LawRepository lawRepository;
    private final LawArticleRepository lawArticleRepository;
    private final LawAliasRepository lawAliasRepository;
    private final RuleParamRepository ruleParamRepository;
    private final DocumentTypeLawRepository documentTypeLawRepository;

    public enum SeedResult { INSERTED, EXISTS, NO_LAW }

    public record SaveResult(Long lawId, int inserted, int updated, int closed, long rows) {
    }

    /** 목록의 법령일련번호(MST)로 저장된 조문이 이미 있으면 true (바뀐 것 없음). */
    @Transactional(readOnly = true)
    public boolean isLoaded(LawListItem item) {
        return findLaw(item.sourceLawId(), item.name())
                .map(law -> lawArticleRepository.existsByLawIdAndSourceMst(law.getLawId(), item.mst()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public long countRows(LawListItem item) {
        return findLaw(item.sourceLawId(), item.name())
                .map(law -> lawArticleRepository.countByLawIdAndSourceMst(law.getLawId(), item.mst()))
                .orElse(0L);
    }

    @Transactional
    public void markSynced(LawListItem item, OffsetDateTime now) {
        findLaw(item.sourceLawId(), item.name()).ifPresent(law -> law.markSynced(now));
    }

    /**
     * 개정본 저장. 같은 (조, 가지, 항, 시행일) 행이 있으면 내용을 고치고, 없으면 넣는다.
     * 그 뒤 다른 개정본에서 온 열린 행을 새 시행일 전날로 닫는다.
     * <p>
     * 알려진 한계 (마감 후 과제): 새 개정본의 시행일이 이전 개정본과 같으면(같은 날 시행하는 정정·타법개정 등)
     * 같은 키의 행은 새 MST 로 덮어쓰지만, 새 개정본에서 사라진 조문·항의 이전 행은 시행일이 같아 닫히지 않고
     * effective_to = null 로 남는다. 이 경우 이전 MST 행을 지워야 하는데, analysis_clause_ref 가 가리킬 수 있어 지우지 않았다.
     */
    @Transactional
    public SaveResult saveVersion(LawListItem item, ParsedLaw parsed, OffsetDateTime now) {
        String kind = parsed.kind() != null ? parsed.kind() : item.kind();
        Law law = findLaw(item.sourceLawId(), item.name()).orElse(null);
        if (law == null) {
            law = lawRepository.save(Law.create(item.name(), kind, item.sourceLawId(), now));
        } else {
            law.sync(item.name(), kind, item.sourceLawId(), now);
        }
        Long lawId = law.getLawId();

        Map<LocalDate, Map<String, LawArticle>> existingByDate = new HashMap<>();
        int inserted = 0;
        int updated = 0;
        List<LawArticle> toInsert = new ArrayList<>();
        for (ParsedArticle a : parsed.articles()) {
            Map<String, LawArticle> existing = existingByDate.computeIfAbsent(a.effectiveFrom(), d -> {
                Map<String, LawArticle> m = new HashMap<>();
                lawArticleRepository.findByLawIdAndEffectiveFrom(lawId, d)
                        .forEach(row -> m.put(key(row.getArticleNo(), row.getArticleBranchNo(), row.getParagraphNo()), row));
                return m;
            });
            String title = TextTruncator.truncate(a.title(), TITLE_MAX);
            LawArticle row = existing.get(key(a.articleNo(), a.branchNo(), a.paragraphNo()));
            if (row != null) {
                row.revise(title, a.content(), item.mst(), parsed.promulgatedAt());
                updated++;
            } else {
                toInsert.add(LawArticle.create(lawId, a.articleNo(), a.branchNo(), a.paragraphNo(), title, a.content(),
                        item.mst(), parsed.promulgatedAt(), a.effectiveFrom()));
                inserted++;
            }
        }
        lawArticleRepository.saveAll(toInsert);
        int closed = lawArticleRepository.closeOtherVersions(lawId, item.mst(), parsed.effectiveDate().minusDays(1));
        long rows = lawArticleRepository.countByLawIdAndSourceMst(lawId, item.mst());
        return new SaveResult(lawId, inserted, updated, closed, rows);
    }

    /** 약칭 넣기. 같은 약칭이 다른 법령에 있으면 새 법령으로 옮긴다. 반환: 넣거나 옮긴 수. 법령이 없으면 건너뜀. */
    @Transactional
    public int upsertAlias(String alias, String lawName, AliasSource source) {
        Optional<Law> law = lawRepository.findByNameNorm(LawNames.normalize(lawName));
        if (law.isEmpty()) {
            log.warn("약칭 {} 의 법령 {} 이 DB 에 없어 건너뜀", alias, lawName);
            return 0;
        }
        String norm = LawNames.normalize(alias);
        if (norm.equals(law.get().getNameNorm())) {
            return 0;
        }
        Optional<LawAlias> existing = lawAliasRepository.findById(norm);
        if (existing.isPresent()) {
            LawAlias a = existing.get();
            // 같은 법령이면 API(법령명약칭)가 우선: MANUAL 로 다시 넣어도 API 표시를 지우지 않는다
            if (a.getLawId().equals(law.get().getLawId())
                    && (a.getSource() == source || a.getSource() == AliasSource.API)) {
                return 0;
            }
            if (!a.getLawId().equals(law.get().getLawId())) {
                log.warn("약칭 {} 를 법령 {} → {} 로 옮김", alias, a.getLawId(), law.get().getLawId());
            }
            a.relink(law.get().getLawId(), source);
            return 1;
        }
        lawAliasRepository.save(LawAlias.create(alias, law.get().getLawId(), source));
        return 1;
    }

    @Transactional
    public boolean linkParent(String childName, String parentName) {
        Optional<Law> child = lawRepository.findByNameNorm(LawNames.normalize(childName));
        Optional<Law> parent = lawRepository.findByNameNorm(LawNames.normalize(parentName));
        if (child.isEmpty() || parent.isEmpty()) {
            log.warn("상위 법령 연결 건너뜀: {} → {}", childName, parentName);
            return false;
        }
        child.get().linkParent(parent.get().getLawId());
        return true;
    }

    /**
     * 문서 종류별 관련 법령 넣기. 이미 있으면 건너뛴다(core 도 바꾸지 않음).
     * document_type 행이 없으면 FK 위반(DataIntegrityViolationException)이 이 트랜잭션 밖으로 나가고 이 트랜잭션만 롤백된다.
     * document_type 은 B 소유라 A 가 직접 조회하지 않고 FK 로 확인한다.
     */
    @Transactional
    public SeedResult insertDocumentTypeLaw(String documentTypeCode, String lawName, boolean core) {
        Optional<Law> law = lawRepository.findByNameNorm(LawNames.normalize(lawName));
        if (law.isEmpty()) {
            return SeedResult.NO_LAW;
        }
        if (documentTypeLawRepository.existsById(new DocumentTypeLawId(documentTypeCode, law.get().getLawId()))) {
            return SeedResult.EXISTS;
        }
        documentTypeLawRepository.saveAndFlush(DocumentTypeLaw.create(documentTypeCode, law.get().getLawId(), core));
        return SeedResult.INSERTED;
    }

    /** 숫자 규칙 기준값 넣기·갱신. 근거 조문은 지금 유효한 행. 반환: 저장했으면 근거 조문 ID. */
    @Transactional
    public Optional<Long> upsertRule(RuleSeed seed) {
        Optional<Law> law = lawRepository.findByNameNorm(LawNames.normalize(seed.lawName()));
        if (law.isEmpty()) {
            log.warn("규칙 {} 의 근거 법령 {} 이 없어 건너뜀", seed.ruleCode(), seed.lawName());
            return Optional.empty();
        }
        Optional<Long> articleId = lawArticleRepository
                .findArticleIds(law.get().getLawId(), seed.articleNo(), seed.branchNo(), LocalDate.now())
                .stream()
                .map(id -> lawArticleRepository.findById(id).orElseThrow())
                .filter(a -> seed.paragraphNo() == null
                        ? a.getParagraphNo() == null || a.getParagraphNo() == 1
                        : seed.paragraphNo().equals(a.getParagraphNo()))
                .map(LawArticle::getLawArticleId)
                .findFirst();
        if (articleId.isEmpty()) {
            log.warn("규칙 {} 의 근거 조문 {} 제{}조 를 찾지 못해 건너뜀", seed.ruleCode(), seed.lawName(), seed.articleNo());
            return Optional.empty();
        }
        Optional<RuleParam> existing = ruleParamRepository.findById(new RuleParamId(seed.ruleCode(), seed.validFrom()));
        if (existing.isPresent()) {
            existing.get().change(seed.value(), articleId.get(), seed.note());
        } else {
            ruleParamRepository.save(RuleParam.create(seed.ruleCode(), seed.validFrom(), seed.value(), articleId.get(), seed.note()));
        }
        return articleId;
    }

    private Optional<Law> findLaw(String sourceLawId, String name) {
        Optional<Law> bySource = sourceLawId == null ? Optional.empty() : lawRepository.findBySourceLawId(sourceLawId);
        return bySource.isPresent() ? bySource : lawRepository.findByNameNorm(LawNames.normalize(name));
    }

    private static String key(int articleNo, int branchNo, Integer paragraphNo) {
        return articleNo + "-" + branchNo + "-" + (paragraphNo == null ? 0 : paragraphNo);
    }
}
