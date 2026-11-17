package com.team.docai.domain.law.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 법령 약칭 (law_alias). 기본 키는 정규화한 약칭. */
@Entity
@Table(name = "law_alias")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LawAlias {

    @Id
    @Column(nullable = false, length = 100)
    private String aliasNorm;

    @Column(nullable = false)
    private Long lawId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AliasSource source;

    @Builder
    private LawAlias(String aliasNorm, Long lawId, AliasSource source) {
        this.aliasNorm = aliasNorm;
        this.lawId = lawId;
        this.source = source;
    }

    public static LawAlias create(String alias, Long lawId, AliasSource source) {
        String norm = LawNames.normalize(alias);
        if (norm == null || norm.isEmpty()) {
            throw new IllegalArgumentException("약칭이 비어 있습니다.");
        }
        return LawAlias.builder().aliasNorm(norm).lawId(lawId).source(source).build();
    }

    public void relink(Long lawId, AliasSource source) {
        this.lawId = lawId;
        this.source = source;
    }
}
