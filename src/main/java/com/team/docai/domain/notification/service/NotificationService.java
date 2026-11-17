package com.team.docai.domain.notification.service;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional(readOnly=true)
public class NotificationService {
 @Transactional
 public void createAnalysisResult(UUID userId,Long analysisId,String type,String title,String subTitle){
  // TODO: 분석 담당 연결용. 알림 저장은 후속 작업에서 구현한다.
 }
}
