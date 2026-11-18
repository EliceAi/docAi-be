package com.team.docai.domain.notification.service;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @lombok.RequiredArgsConstructor @Transactional(readOnly=true)
public class NotificationService {
 private final com.team.docai.domain.notification.repository.NotificationRepository repository;
 private final com.team.docai.domain.notification.repository.AnalysisNotificationLock analysisLock;
 public long unreadCount(UUID userId){return repository.countByUserIdAndReadFalse(userId);}
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
}
