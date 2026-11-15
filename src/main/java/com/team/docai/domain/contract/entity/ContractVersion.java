package com.team.docai.domain.contract.entity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
import java.time.OffsetDateTime;
import com.team.docai.global.common.BaseEntity;
@Entity @Table(name="contract_version") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ContractVersion extends BaseEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long versionId;
 private Long contractId; private int versionNo; private int pageCount; private Long latestAnalysisId;
 @Builder(access=AccessLevel.PRIVATE) private ContractVersion(Long contractId,int pageCount){this.contractId=contractId;this.pageCount=pageCount;this.versionNo=1;}
 public static ContractVersion create(Long contractId,int pageCount){return new ContractVersion(contractId,pageCount);}
}
