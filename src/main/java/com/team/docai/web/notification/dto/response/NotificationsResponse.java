package com.team.docai.web.notification.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.docai.domain.notification.dto.NotificationsInfo;
import java.time.OffsetDateTime;
import java.util.List;
public record NotificationsResponse(long unreadCount,List<Item> items){
 public record Item(Long notificationId,String type,String title,String subTitle,Target target,boolean read,OffsetDateTime createdAt){}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record Target(Long scheduleId,Long analysisId){}
 public static NotificationsResponse from(NotificationsInfo i){
  return new NotificationsResponse(i.unreadCount(),i.items().stream().map(n->new Item(
   n.notificationId(),n.type(),n.title(),n.subTitle(),
   n.target()==null?null:new Target(n.target().scheduleId(),n.target().analysisId()),
   n.read(),n.createdAt())).toList());
 }
}
