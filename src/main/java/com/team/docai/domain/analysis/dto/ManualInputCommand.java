package com.team.docai.domain.analysis.dto;

/**
 * 미인식 항목 입력. label 은 주요 조건 이름 (keyTerms[].label), value 는 사용자가 직접 넣은 값 (100자 이하, 앞뒤 공백 없음).
 */
public record ManualInputCommand(String label, String value) {
}
