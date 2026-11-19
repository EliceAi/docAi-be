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
 @Builder(access=AccessLevel.PRIVATE)
 private Schedule(UUID userId,Long contractId,Long candidateId,String dateKind,String title,LocalDate date,ScheduleSource source,String location){
  this.userId=userId;this.contractId=contractId;this.candidateId=candidateId;this.dateKind=dateKind;
  this.title=title;this.date=date;this.source=source;this.location=location;
 }
 public static Schedule create(UUID userId,Long contractId,Long candidateId,String dateKind,String title,LocalDate date,ScheduleSource source,String location){
  return Schedule.builder().userId(userId).contractId(contractId).candidateId(candidateId).dateKind(dateKind).title(title).date(date).source(source).location(location).build();
 }
 public void updateTitle(String title){this.title=title;}
 public void updateDate(LocalDate date){this.date=date;this.source=ScheduleSource.MANUAL;this.location=null;}
 public void updateFromCandidate(Long candidateId,String dateKind,String title,LocalDate date,ScheduleSource source,String location){
  this.candidateId=candidateId;this.dateKind=dateKind;this.title=title;this.date=date;this.source=source;this.location=location;
 }
}
