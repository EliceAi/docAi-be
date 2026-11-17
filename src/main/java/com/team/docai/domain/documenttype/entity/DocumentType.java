package com.team.docai.domain.documenttype.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="document_type") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class DocumentType {
 @Id private String code;
 private String name;
 private String category;
 private String situation;
 private String description;
 private String checkLevel;
 private boolean supported;
 private boolean popular;
 private int sortOrder;
}
