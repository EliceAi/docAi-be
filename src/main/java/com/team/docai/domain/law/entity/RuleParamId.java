package com.team.docai.domain.law.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/** rule_param 의 복합 기본 키 (rule_code, valid_from). */
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RuleParamId implements Serializable {

    private String ruleCode;
    private LocalDate validFrom;
}
