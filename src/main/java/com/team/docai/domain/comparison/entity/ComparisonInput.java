package com.team.docai.domain.comparison.entity;

import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="comparison_input",uniqueConstraints=@UniqueConstraint(columnNames={"previous_id","current_id","label"})) @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ComparisonInput extends BaseEntity {
    @Id private UUID id;@Column(nullable=false) private UUID userId;
    @Column(nullable=false) private UUID previousId;@Column(nullable=false) private UUID currentId;
    @Column(nullable=false,length=150) private String label;
    @Column(nullable=false,length=1000) private String beforeValue;
    @Column(nullable=false,length=1000) private String afterValue;
    @Builder private ComparisonInput(UUID id,UUID userId,UUID previousId,UUID currentId,String label,String beforeValue,String afterValue){this.id=id;this.userId=userId;this.previousId=previousId;this.currentId=currentId;this.label=label;this.beforeValue=beforeValue;this.afterValue=afterValue;}
    public static ComparisonInput create(UUID user,UUID previous,UUID current,String label,String before,String after){return ComparisonInput.builder().id(UUID.randomUUID()).userId(user).previousId(previous).currentId(current).label(label).beforeValue(before).afterValue(after).build();}
    public void correct(String before,String after){beforeValue=before;afterValue=after;}
}
