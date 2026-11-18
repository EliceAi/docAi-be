package com.team.docai.domain.notification.repository;
import com.team.docai.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface NotificationRepository extends JpaRepository<Notification,Long> {
 boolean existsByAnalysisIdAndType(Long analysisId,String type);
 long countByUserIdAndReadFalse(UUID userId);
}
