package com.team.docai.domain.law.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.Map;

/** 숫자 규칙 기준값 (rule_param). 근거 조문은 필수 (설계_점검 13번). */
@Entity
@Table(name = "rule_param")
@IdClass(RuleParamId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleParam {

    @Id
    @Column(nullable = false, length = 40)
    private String ruleCode;

    @Id
    @Column(nullable = false)
    private LocalDate validFrom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> valueJson;

    @Column(nullable = false)
    private Long lawArticleId;

    @Column(length = 200)
    private String note;

    @Builder
    private RuleParam(String ruleCode, LocalDate validFrom, Map<String, Object> valueJson, Long lawArticleId, String note) {
        this.ruleCode = ruleCode;
        this.validFrom = validFrom;
        this.valueJson = valueJson;
        this.lawArticleId = lawArticleId;
        this.note = note;
    }

    public static RuleParam create(String ruleCode, LocalDate validFrom, Map<String, Object> valueJson,
                                   Long lawArticleId, String note) {
        if (lawArticleId == null) {
            throw new IllegalArgumentException("숫자 규칙은 근거 조문이 필요합니다.");
        }
        return RuleParam.builder().ruleCode(ruleCode).validFrom(validFrom).valueJson(valueJson)
                .lawArticleId(lawArticleId).note(note).build();
    }

    public void change(Map<String, Object> valueJson, Long lawArticleId, String note) {
        if (lawArticleId == null) {
            throw new IllegalArgumentException("숫자 규칙은 근거 조문이 필요합니다.");
        }
        this.valueJson = valueJson;
        this.lawArticleId = lawArticleId;
        this.note = note;
    }
}
