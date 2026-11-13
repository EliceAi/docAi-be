package com.team.docai.domain.document.entity;

import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="document_check",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","request_key"})) @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class DocumentCheck extends BaseEntity {
    @Id private UUID id;
    @Column(nullable=false) private UUID userId;
    @Column(nullable=false) private UUID requestKey;
    @Column(nullable=false,length=100) private String title;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private DocumentType documentType;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private CheckStatus status;
    @Column(nullable=false) private int pageCount;
    @Column(nullable=false) private boolean archived;
    @Column(nullable=false) private int revision;
    private UUID previousId;
    @Column(columnDefinition="text") private String resultJson;
    private String failureReason;
    @Column(nullable=false) private String scenario;
    @Version private long lockVersion;
    @Builder private DocumentCheck(UUID id,UUID userId,UUID requestKey,String title,DocumentType documentType,int pageCount,String scenario) {
        this.id=id;this.userId=userId;this.requestKey=requestKey;this.title=title;this.documentType=documentType;this.pageCount=pageCount;this.scenario=scenario;
        this.status=CheckStatus.PENDING;this.revision=1;
    }
    public static DocumentCheck create(UUID id,UUID user,UUID key,String title,DocumentType type,int pages,String scenario){return DocumentCheck.builder().id(id).userId(user).requestKey(key).title(title).documentType(type).pageCount(pages).scenario(scenario).build();}
    public void start(){status=CheckStatus.RUNNING;failureReason=null;}
    public void complete(String json,boolean partial){resultJson=json;status=partial?CheckStatus.PARTIAL:CheckStatus.COMPLETED;}
    public void fail(String reason){status=CheckStatus.FAILED;failureReason=reason;}
    public void retry(){status=CheckStatus.PENDING;failureReason=null;scenario="normal";}
    public void archive(){archived=true;}
    public void revise(UUID previous,int version){previousId=previous;revision=version;}
}
