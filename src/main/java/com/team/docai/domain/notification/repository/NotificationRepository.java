package com.team.docai.domain.notification.repository;
import com.team.docai.domain.notification.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.*;
public interface NotificationRepository extends JpaRepository<Notification,Long> {
 boolean existsByAnalysisIdAndType(Long analysisId,String type);
 boolean existsByScheduleIdAndTypeAndDueOffsetAndDueDate(Long scheduleId,String type,Integer dueOffset,LocalDate dueDate);
 long countByUserIdAndReadFalse(UUID userId);
 List<Notification> findByUserIdOrderByCreatedAtDescNotificationIdDesc(UUID userId);
 List<Notification> findByUserIdOrderByNotificationIdDesc(UUID userId,Pageable pageable);
 List<Notification> findByUserIdAndNotificationIdLessThanOrderByNotificationIdDesc(UUID userId,Long beforeId,Pageable pageable);
 @Modifying(clearAutomatically=true,flushAutomatically=true)
 @Query("delete from Notification n where n.scheduleId=:scheduleId and n.read=false and n.dueDate=:dueDate")
 int deleteUnreadByScheduleIdAndDueDate(@Param("scheduleId") Long scheduleId,@Param("dueDate") LocalDate dueDate);
}
