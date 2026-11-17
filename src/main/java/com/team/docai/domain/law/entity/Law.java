package com.team.docai.domain.law.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 법령 (law). 법제처 현행법령 목록·본문에서 채운다.
 * law_kind 는 API 법종구분 값 그대로 (법률 / 대통령령 ...), parent_law_id 는 팀이 정한 시드로 연결한다 (설계_점검 35번).
 */
@Entity
@Table(name = "law")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Law {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long lawId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100, unique = true)
    private String nameNorm;

    @Column(nullable = false, length = 20)
    private String lawKind;

    private Long parentLawId;

    @Column(nullable = false, length = 20)
    private String sourceLawId;

    @Column(nullable = false)
    private OffsetDateTime syncedAt;

    @Builder
    private Law(String name, String nameNorm, String lawKind, String sourceLawId, OffsetDateTime syncedAt) {
        this.name = name;
        this.nameNorm = nameNorm;
        this.lawKind = lawKind;
        this.sourceLawId = sourceLawId;
        this.syncedAt = syncedAt;
    }

    public static Law create(String name, String lawKind, String sourceLawId, OffsetDateTime syncedAt) {
        return Law.builder()
                .name(name)
                .nameNorm(LawNames.normalize(name))
                .lawKind(lawKind)
                .sourceLawId(sourceLawId)
                .syncedAt(syncedAt)
                .build();
    }

    /** 동기화 결과 반영. 이름 정규화 값은 이름에서 다시 만든다. */
    public void sync(String name, String lawKind, String sourceLawId, OffsetDateTime syncedAt) {
        this.name = name;
        this.nameNorm = LawNames.normalize(name);
        this.lawKind = lawKind;
        this.sourceLawId = sourceLawId;
        this.syncedAt = syncedAt;
    }

    public void markSynced(OffsetDateTime syncedAt) {
        this.syncedAt = syncedAt;
    }

    public void linkParent(Long parentLawId) {
        if (lawId != null && lawId.equals(parentLawId)) {
            throw new IllegalArgumentException("법령은 자기 자신을 상위 법령으로 가질 수 없습니다.");
        }
        this.parentLawId = parentLawId;
    }
}
