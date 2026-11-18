package com.team.docai.domain.notification.entity;
import com.team.docai.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.UUID;
@Entity @Table(name="notification") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Notification extends BaseEntity {
 public static Notification analysisResult(UUID userId,Long analysisId,String type,String title,String subTitle){
  var notification=new Notification();notification.userId=userId;notification.analysisId=analysisId;
  notification.type=type;notification.title=title;notification.subTitle=subTitle;notification.read=false;
  return notification;
 }
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long notificationId;
 private UUID userId;
 private String type;
 private String title;
 private String subTitle;
 private Long scheduleId;
 private Long analysisId;
 private Integer dueOffset;
 private LocalDate dueDate;
 @Column(name="is_read") private boolean read;
}
