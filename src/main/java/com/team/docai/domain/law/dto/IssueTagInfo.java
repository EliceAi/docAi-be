package com.team.docai.domain.law.dto;

/** 문제 꼬리표 (issue_tag). GPT 1차 프롬프트에 넣는 목록과 저장 전 확인에 쓴다. */
public record IssueTagInfo(String code, String name) {
}
