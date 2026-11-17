package com.team.docai.web;

import com.team.docai.web.documenttype.DocumentTypeController;
import com.team.docai.web.contract.ContractController;
import com.team.docai.web.schedule.ScheduleController;
import com.team.docai.web.notification.NotificationController;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.domain.documenttype.dto.DocumentTypesInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.domain.schedule.dto.UpcomingScheduleInfo;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.global.auth.TokenService;
import com.team.docai.global.config.*;
import com.team.docai.global.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({DocumentTypeController.class,ContractController.class,ScheduleController.class,NotificationController.class})
@Import({SecurityConfig.class,TokenConfig.class,TokenService.class})
class BServiceWebTest {
 @Autowired MockMvc mvc;
 @Autowired TokenService tokens;
 @MockitoBean com.team.docai.global.auth.AnonymousRateLimiter limiter;
 @MockitoBean DocumentTypeService types;
 @MockitoBean ContractService contracts;
 @MockitoBean ScheduleService schedules;
 @MockitoBean NotificationService notifications;
 final UUID user=UUID.randomUUID();
 String auth(){return "Bearer "+tokens.issue(user);}
 @Test void endpointsRejectMissingAndInvalidTokens() throws Exception {
  for(String url:List.of("/api/document-types","/api/contracts/1/pages/1","/api/schedules","/api/schedules/upcoming","/api/notifications/unread-count")){
   mvc.perform(get(url)).andExpect(status().isUnauthorized());
   mvc.perform(get(url).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
  }
  mvc.perform(delete("/api/schedules/1")).andExpect(status().isUnauthorized());
  verifyNoInteractions(types,contracts,schedules,notifications);
 }
 @Test void imageReturnsJpegAndBusinessErrorsStayJson() throws Exception {
  byte[] image={(byte)255,(byte)216,(byte)255};when(contracts.readPage(user,1L,1,2)).thenReturn(image);
  mvc.perform(get("/api/contracts/1/pages/1").param("version","2").header("Authorization",auth())).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg")).andExpect(content().bytes(image)).andExpect(header().string("Cache-Control","no-store"));
  when(contracts.readPage(user,2L,1,null)).thenThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
  mvc.perform(get("/api/contracts/2/pages/1").header("Authorization",auth())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
 }
 @Test void scheduleAliasesUseAuthenticatedUserAndCorrectDefault() throws Exception {
  when(schedules.upcoming(user,2)).thenReturn(List.of(new UpcomingScheduleInfo(1L,"오늘",LocalDate.of(2026,11,18),0,null,null)));
  for(String url:List.of("/api/schedules","/api/schedules/upcoming"))mvc.perform(get(url).header("Authorization",auth())).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].dDay").value(0));
  verify(schedules,times(2)).upcoming(user,2);
  mvc.perform(get("/api/schedules").param("limit","abc").header("Authorization",auth())).andExpect(status().isBadRequest());
  mvc.perform(delete("/api/schedules/1").header("Authorization",auth())).andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());verify(schedules).delete(user,1L);
 }
 @Test void documentGroupsAndUnreadCountUseApiEnvelope() throws Exception {
  when(types.list("HOME","집")).thenReturn(new DocumentTypesInfo(0,List.of(),List.of()));
  mvc.perform(get("/api/document-types").param("category","HOME").param("keyword","집").header("Authorization",auth())).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(0)).andExpect(jsonPath("$.data.groups").isArray());
  when(notifications.unreadCount(user)).thenReturn(3L);
  mvc.perform(get("/api/notifications/unread-count").header("Authorization",auth())).andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(3));
 }
}
