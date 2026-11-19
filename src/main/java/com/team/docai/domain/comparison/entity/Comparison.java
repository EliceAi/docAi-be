package com.team.docai.domain.comparison.entity;
import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="comparison") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Comparison extends BaseEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long comparisonId;
 @Column(nullable=false) private UUID userId;
 @Column(nullable=false) private Long previousContractId;
 @Column(nullable=false) private Long previousVersionId;
 private Long revisedContractId;
 @Column(nullable=false) private Long revisedVersionId;
 @Column(nullable=false) private Long revisedAnalysisId;
 @Column(nullable=false,length=20) private String compatibility;
 @Column(nullable=false,length=20) private String status;
 @Column(length=300) private String unchangedSummary;
 private OffsetDateTime closedAt;
 @Builder(access=AccessLevel.PRIVATE)
 private Comparison(UUID userId,Long previousContractId,Long previousVersionId,Long revisedContractId,Long revisedVersionId,Long revisedAnalysisId,String compatibility,String status,String unchangedSummary){
  this.userId=userId;this.previousContractId=previousContractId;this.previousVersionId=previousVersionId;
  this.revisedContractId=revisedContractId;this.revisedVersionId=revisedVersionId;this.revisedAnalysisId=revisedAnalysisId;
  this.compatibility=compatibility;this.status=status;this.unchangedSummary=unchangedSummary;
 }
 public static Comparison create(UUID userId,Long previousContractId,Long previousVersionId,Long revisedContractId,Long revisedVersionId,Long revisedAnalysisId,String compatibility,String unchangedSummary){
  return Comparison.builder().userId(userId).previousContractId(previousContractId).previousVersionId(previousVersionId)
   .revisedContractId(revisedContractId).revisedVersionId(revisedVersionId).revisedAnalysisId(revisedAnalysisId)
   .compatibility(compatibility).status("PENDING_CONFIRM").unchangedSummary(unchangedSummary).build();
 }
 public void confirm(OffsetDateTime closedAt){this.status="CONFIRMED";this.revisedContractId=null;this.closedAt=closedAt;}
 public void discard(OffsetDateTime closedAt){this.status="DISCARDED";this.closedAt=closedAt;}
 public void supersede(OffsetDateTime closedAt){this.status="SUPERSEDED";this.closedAt=closedAt;}
}
