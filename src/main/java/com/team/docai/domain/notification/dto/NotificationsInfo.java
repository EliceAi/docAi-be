package com.team.docai.domain.notification.dto;
import java.time.OffsetDateTime;
import java.util.List;
public record NotificationsInfo(long unreadCount,List<Item> items){
 public record Item(Long notificationId,String type,String title,String subTitle,Target target,boolean read,OffsetDateTime createdAt){}
 public record Target(Long scheduleId,Long analysisId){}
}
