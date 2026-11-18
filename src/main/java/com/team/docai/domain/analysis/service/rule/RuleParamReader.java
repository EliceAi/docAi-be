package com.team.docai.domain.analysis.service.rule;

import com.team.docai.domain.law.entity.RuleParam;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * 숫자 규칙 기준값(rule_param) 읽기. 기준일에 적용되는 행 = valid_from &lt;= 기준일 중 가장 늦은 것.
 * LawSearchService 에 같은 조회가 없어 이 패키지에 둔다 (공용 Repository·Service 를 고치지 않기 위해).
 */
@Component
@RequiredArgsConstructor
public class RuleParamReader {

    /** 기준값 한 행 (엔티티를 밖으로 내보내지 않는다). */
    public record RuleStandard(String ruleCode, LocalDate validFrom, Map<String, Object> value, Long lawArticleId) {
    }

    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<RuleStandard> find(String ruleCode, LocalDate baseDate) {
        return entityManager.createQuery("select r from RuleParam r where r.ruleCode = :code"
                        + " and r.validFrom <= :baseDate order by r.validFrom desc", RuleParam.class)
                .setParameter("code", ruleCode)
                .setParameter("baseDate", baseDate)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(r -> new RuleStandard(r.getRuleCode(), r.getValidFrom(),
                        r.getValueJson() == null ? Map.of() : Map.copyOf(r.getValueJson()), r.getLawArticleId()));
    }
}
