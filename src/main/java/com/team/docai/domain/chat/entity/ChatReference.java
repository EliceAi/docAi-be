package com.team.docai.domain.chat.entity;

/** 참고한 정보 하나 (refs_json.references 의 항목) */
public record ChatReference(ReferenceType type, String label) {
}
