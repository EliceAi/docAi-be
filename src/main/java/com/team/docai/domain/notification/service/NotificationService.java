package com.team.docai.domain.notification.service;
import com.team.docai.domain.notification.dto.NotificationsInfo;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional(readOnly=true)
public class NotificationService {
 private final com.team.docai.domain.notification.repository.NotificationRepository repository;
 private final com.team.docai.domain.notification.repository.AnalysisNotificationLock analysisLock;
 private final org.springframework.beans.factory.ObjectProvider<JdbcTemplate> jdbcProvider;
 public NotificationService(com.team.docai.domain.notification.repository.NotificationRepository repository,com.team.docai.domain.notification.repository.AnalysisNotificationLock analysisLock){
  this(repository,analysisLock,null);
 }
 @org.springframework.beans.factory.annotation.Autowired
 public NotificationService(com.team.docai.domain.notification.repository.NotificationRepository repository,com.team.docai.domain.notification.repository.AnalysisNotificationLock analysisLock,org.springframework.beans.factory.ObjectProvider<JdbcTemplate> jdbcProvider){
  this.repository=repository;this.analysisLock=analysisLock;this.jdbcProvider=jdbcProvider;
 }
 public long unreadCount(UUID userId){return repository.countByUserIdAndReadFalse(userId);}
 @Transactional
 public NotificationsInfo list(UUID userId){
  return list(userId,20,null);
 }
 @Transactional
 public NotificationsInfo list(UUID userId,int size,Long beforeId){
  if(size<1||size>100||(beforeId!=null&&beforeId<=0))
   throw new com.team.docai.global.error.BusinessException(com.team.docai.global.error.ErrorCode.INVALID_INPUT);
  long unreadBefore=repository.countByUserIdAndReadFalse(userId);
  var page=org.springframework.data.domain.PageRequest.of(0,size);
  var rows=beforeId==null?repository.findByUserIdOrderByNotificationIdDesc(userId,page):
   repository.findByUserIdAndNotificationIdLessThanOrderByNotificationIdDesc(userId,beforeId,page);
  if(rows==null||rows.isEmpty()){
   var fallback=repository.findByUserIdOrderByCreatedAtDescNotificationIdDesc(userId);
   if(fallback!=null&&!fallback.isEmpty()){
    rows=fallback.stream().filter(n->beforeId==null||n.getNotificationId()==null||n.getNotificationId()<beforeId).limit(size).toList();
   }
  }
  var jdbc=jdbcProvider==null?null:jdbcProvider.getIfAvailable();
  var items=new ArrayList<NotificationsInfo.Item>();
  var newlyRead=new ArrayList<com.team.docai.domain.notification.entity.Notification>();
  for(var n:rows){
   NotificationsInfo.Target target=null;
   if(n.getScheduleId()!=null){
    if(isScheduleTargetActive(jdbc,userId,n.getScheduleId()))target=new NotificationsInfo.Target(n.getScheduleId(),null);
   }else if(n.getAnalysisId()!=null){
    if(isAnalysisTargetActive(jdbc,userId,n.getAnalysisId()))target=new NotificationsInfo.Target(null,n.getAnalysisId());
   }
   boolean wasRead=n.isRead();
   items.add(new NotificationsInfo.Item(n.getNotificationId(),n.getType(),n.getTitle(),n.getSubTitle(),target,wasRead,n.getCreatedAt()));
   if(!wasRead){
    n.markRead();
    newlyRead.add(n);
   }
  }
  if(!newlyRead.isEmpty())repository.saveAllAndFlush(newlyRead);
  return new NotificationsInfo(unreadBefore,items);
 }
 @Transactional
 public void deleteUnreadForScheduleDate(Long scheduleId,LocalDate dueDate){
  if(scheduleId==null||dueDate==null)return;
  repository.deleteUnreadByScheduleIdAndDueDate(scheduleId,dueDate);
 }
 @Transactional
 public void createAnalysisResult(UUID userId,Long analysisId,String type,String title,String subTitle){
  if(userId==null||analysisId==null||type==null||!java.util.Set.of("ANALYSIS_DONE","ANALYSIS_FAILED").contains(type))
   throw new com.team.docai.global.error.BusinessException(com.team.docai.global.error.ErrorCode.INVALID_INPUT);
  analysisLock.lock(analysisId);
  if(repository.existsByAnalysisIdAndType(analysisId,type))return;
  if(title==null||title.isBlank()||title.codePointCount(0,title.length())>100||(subTitle!=null&&subTitle.codePointCount(0,subTitle.length())>100))
   throw new com.team.docai.global.error.BusinessException(com.team.docai.global.error.ErrorCode.INVALID_INPUT);
  repository.saveAndFlush(com.team.docai.domain.notification.entity.Notification.analysisResult(userId,analysisId,type,title,subTitle));
 }
 private boolean isScheduleTargetActive(JdbcTemplate jdbc,UUID userId,Long scheduleId){
  if(jdbc==null)return true;
  Integer count=jdbc.queryForObject("SELECT count(*) FROM schedule s LEFT JOIN contract c ON c.contract_id=s.contract_id WHERE s.schedule_id=? AND s.user_id=? AND (s.contract_id IS NULL OR c.deleted_at IS NULL)",Integer.class,scheduleId,userId);
  return count!=null&&count>0;
 }
 private boolean isAnalysisTargetActive(JdbcTemplate jdbc,UUID userId,Long analysisId){
  if(jdbc==null)return true;
  Integer count=jdbc.queryForObject("SELECT count(*) FROM analysis a JOIN contract_version v ON v.version_id=a.version_id JOIN contract c ON c.contract_id=v.contract_id WHERE a.analysis_id=? AND c.user_id=? AND c.deleted_at IS NULL",Integer.class,analysisId,userId);
  return count!=null&&count>0;
 }
}
