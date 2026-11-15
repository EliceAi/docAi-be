package com.team.docai.domain.contract.entity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
import java.time.OffsetDateTime;
import com.team.docai.global.common.BaseEntity;
@Entity @Table(name="contract_page") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ContractPage {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long pageId;
 private Long versionId; private int pageNo; private String imageKey; private int maskedCount; private OffsetDateTime replacedAt;
 @Builder(access=AccessLevel.PRIVATE) private ContractPage(Long versionId,int pageNo,String imageKey,int maskedCount){this.versionId=versionId;this.pageNo=pageNo;this.imageKey=imageKey;this.maskedCount=maskedCount;}
 public static ContractPage create(Long versionId,int pageNo,String imageKey,int maskedCount){return new ContractPage(versionId,pageNo,imageKey,maskedCount);}
}
