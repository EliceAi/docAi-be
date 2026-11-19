package com.team.docai.web.notification;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.web.notification.dto.response.NotificationsResponse;
import com.team.docai.web.notification.dto.response.UnreadCountResponse;
import com.team.docai.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
@RestController @RequestMapping("/api/notifications") @RequiredArgsConstructor
public class NotificationController {
 private final NotificationService service;
 @GetMapping public ApiResponse<NotificationsResponse> list(@AuthenticationPrincipal UUID userId,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) Long beforeId){return ApiResponse.success(NotificationsResponse.from(service.list(userId,size,beforeId)));}
 @GetMapping("/unread-count") public ApiResponse<UnreadCountResponse> unread(@AuthenticationPrincipal UUID userId){return ApiResponse.success(new UnreadCountResponse(service.unreadCount(userId)));}
}
