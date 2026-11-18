package com.team.docai.domain.notification;
import com.team.docai.domain.notification.repository.*;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.domain.notification.entity.Notification;
import com.team.docai.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class AnalysisNotificationTest {
 @Test void savesUnreadResultAndSkipsDuplicateAfterLock(){
  var repo=mock(NotificationRepository.class);var lock=mock(AnalysisNotificationLock.class);var service=new NotificationService(repo,lock);var user=UUID.randomUUID();
  when(repo.existsByAnalysisIdAndType(1L,"ANALYSIS_DONE")).thenReturn(false,true);
  service.createAnalysisResult(user,1L,"ANALYSIS_DONE","완료","계약서");service.createAnalysisResult(user,1L,"ANALYSIS_DONE","완료","계약서");
  var saved=ArgumentCaptor.forClass(Notification.class);verify(repo).saveAndFlush(saved.capture());assertEquals(user,saved.getValue().getUserId());assertEquals(1L,saved.getValue().getAnalysisId());assertFalse(saved.getValue().isRead());assertEquals("계약서",saved.getValue().getSubTitle());
  var order=inOrder(lock,repo);order.verify(lock).lock(1L);order.verify(repo).existsByAnalysisIdAndType(1L,"ANALYSIS_DONE");order.verify(repo).saveAndFlush(any());order.verify(lock).lock(1L);order.verify(repo).existsByAnalysisIdAndType(1L,"ANALYSIS_DONE");
  service.createAnalysisResult(user,1L,"ANALYSIS_FAILED","실패",null);verify(repo,times(2)).saveAndFlush(any());
 }
 @Test void rejectsOtherNotificationTypes(){
  var repo=mock(NotificationRepository.class);var lock=mock(AnalysisNotificationLock.class);var service=new NotificationService(repo,lock);
  assertThrows(BusinessException.class,()->service.createAnalysisResult(UUID.randomUUID(),1L,"SCHEDULE_DUE","제목",null));verifyNoInteractions(repo,lock);
 }
}
