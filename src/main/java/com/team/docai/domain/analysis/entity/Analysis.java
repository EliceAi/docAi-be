package com.team.docai.domain.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 분석 (analysis). created_at/updated_at 이 없는 테이블이라 BaseEntity 를 상속하지 않는다.
 * version_id(contract_version), similar_contract_id(contract)는 B 테이블이라 ID 로만 가진다.
 * 상태 전환(ANALYZING → COMPLETED/FAILED)은 변경 감지가 아니라 AnalysisRepository 의 조건부 업데이트로 한다.
 * {@code @DynamicUpdate}: 변경 감지 UPDATE 는 바뀐 칸만 쓴다. 조건부 업데이트(상태 전환, similar_contract_id 비우기)가
 * 바꾼 칸을, 같은 트랜잭션에서 그 전에 읽어 둔 낡은 엔티티가 다른 칸을 바꾸며 통째로 되돌리지 않게 하기 위해서다.
 */
@Entity
@DynamicUpdate
@Table(name = "analysis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Analysis {

    public static final int DOCUMENT_TYPE_CODE_MAX_LENGTH = 40;
    public static final int DETECTED_TYPE_NAME_MAX_LENGTH = 50;
    public static final int PROMPT_VERSION_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long analysisId;

    @Column(nullable = false)
    private Long versionId;

    @Column(nullable = false, length = DOCUMENT_TYPE_CODE_MAX_LENGTH)
    private String documentTypeCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStep progressStep;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AnalysisFailReason failReason;

    private Integer failPageNo;

    @Column(length = DETECTED_TYPE_NAME_MAX_LENGTH)
    private String detectedTypeName;

    /** 일부만 읽은 쪽 번호 배열 (예: [2, 3]) */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Integer> partialPages;

    private LocalDate lawBaseDate;

    @Column(nullable = false, length = PROMPT_VERSION_MAX_LENGTH)
    private String promptVersion;

    /** 1차 추출 원본 (쪽 묶음 결과를 합친 JSON 그대로) */
    @JdbcTypeCode(SqlTypes.JSON)
    private String extractionJson;

    /** 전체 목차 JSON (조항 키, 제목, 한 줄 요약). 2차 호출 맥락용 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String clauseIndexJson;

    /** 서명 전에 물어볼 것 */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> questionsJson;

    /** 이 계약서만으로 알 수 없는 것 */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> limitationsJson;

    /** 8단계 검증 기록 JSON (지운 인용, 차단한 표현, 제외한 조항, 자른 칸, 버린 꼬리표) */
    @JdbcTypeCode(SqlTypes.JSON)
    private String validationLogJson;

    private Long similarContractId;

    private OffsetDateTime heartbeatAt;

    @Column(nullable = false)
    private OffsetDateTime startedAt;

    private OffsetDateTime completedAt;

    @Builder
    private Analysis(Long versionId, String documentTypeCode, AnalysisStatus status, AnalysisStep progressStep,
                     String promptVersion, OffsetDateTime heartbeatAt, OffsetDateTime startedAt) {
        this.versionId = versionId;
        this.documentTypeCode = documentTypeCode;
        this.status = status;
        this.progressStep = progressStep;
        this.promptVersion = promptVersion;
        this.heartbeatAt = heartbeatAt;
        this.startedAt = startedAt;
    }

    /**
     * 분석 요청 때 만든다. ANALYZING · READING_TEXT, documentTypeCode 는 요청 시점의 계약서 종류.
     */
    public static Analysis create(Long versionId, String documentTypeCode, String promptVersion, OffsetDateTime now) {
        return Analysis.builder()
                .versionId(versionId)
                .documentTypeCode(documentTypeCode)
                .status(AnalysisStatus.ANALYZING)
                .progressStep(AnalysisStep.READING_TEXT)
                .promptVersion(promptVersion)
                .heartbeatAt(now)
                .startedAt(now)
                .build();
    }

    /**
     * 4단계(1차 묶음 병합) 결과 기록. 상태는 바꾸지 않는다 (상태 전환은 조건부 UPDATE).
     * 진행 단계는 같은 트랜잭션에서 조건부 UPDATE(advance)로 FINDING_TERMS 가 된 뒤 부른다.
     */
    public void recordExtraction(String extractionJson, String clauseIndexJson, List<Integer> partialPages,
                                 LocalDate lawBaseDate, String validationLogJson) {
        this.extractionJson = extractionJson;
        this.clauseIndexJson = clauseIndexJson;
        this.partialPages = partialPages == null ? null : List.copyOf(partialPages);
        this.lawBaseDate = lawBaseDate;
        this.validationLogJson = validationLogJson;
    }
}
