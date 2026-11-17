package com.team.docai.domain.law.dto;

import java.time.LocalDate;
import java.util.Map;

/** 숫자 규칙 기준값 시드. 근거 조문은 (법령명, 조, 가지, 항)으로 적고 저장할 때 현행 행 ID 로 바꾼다. */
public record RuleSeed(String ruleCode, LocalDate validFrom, Map<String, Object> value,
                       String lawName, int articleNo, int branchNo, Integer paragraphNo, String note) {
}
