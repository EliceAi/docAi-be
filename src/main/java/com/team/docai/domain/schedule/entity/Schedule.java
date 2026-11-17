package com.team.docai.domain.schedule.entity;
import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.UUID;
@Entity @Table(name="schedule") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Schedule extends BaseEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long scheduleId;
 @Column(nullable=false) private UUID userId;
 private Long contractId;
 private Long candidateId;
 private String dateKind;
 @Column(nullable=false,length=50) private String title;
 @Column(nullable=false) private LocalDate date;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=10) private ScheduleSource source;
 @Column(length=100) private String location;
}
