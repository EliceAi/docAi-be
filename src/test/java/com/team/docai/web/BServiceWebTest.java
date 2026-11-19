package com.team.docai.web;

import com.team.docai.web.documenttype.DocumentTypeController;
import com.team.docai.web.contract.ContractController;
import com.team.docai.web.schedule.ScheduleController;
import com.team.docai.web.notification.NotificationController;
import com.team.docai.web.comparison.ComparisonController;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.domain.documenttype.dto.DocumentTypesInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.contract.dto.*;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.domain.schedule.dto.*;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.domain.notification.dto.NotificationsInfo;
import com.team.docai.domain.comparison.service.ComparisonService;
import com.team.docai.domain.comparison.dto.*;
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
import java.time.OffsetDateTime;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({DocumentTypeController.class,ContractController.class,ScheduleController.class,NotificationController.class,ComparisonController.class})
@Import({SecurityConfig.class,TokenConfig.class,TokenService.class})
class BServiceWebTest {
 @Autowired MockMvc mvc;
 @Autowired TokenService tokens;
 @MockitoBean com.team.docai.global.auth.AnonymousRateLimiter limiter;
 @MockitoBean DocumentTypeService types;
 @MockitoBean ContractService contracts;
 @MockitoBean ScheduleService schedules;
 @MockitoBean NotificationService notifications;
 @MockitoBean ComparisonService comparisons;
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
 @Test void contractArchiveListAndDetailEndpointsMatchFrontendSpec() throws Exception {
  UUID key=UUID.randomUUID();
  when(contracts.archive(eq(user),eq(12L),eq(key),any())).thenReturn(new ArchiveContractInfo(12L,true,1,List.of(new ArchiveContractInfo.SavedSchedule(51L,"첫 출근",LocalDate.of(2026,12,1)))));
  mvc.perform(post("/api/contracts/12/archive").header("Authorization",auth()).header("Idempotency-Key",key.toString()).contentType("application/json")
   .content("{\"schedules\":[{\"candidateId\":301,\"title\":\"첫 출근\",\"date\":\"2026-12-01\",\"source\":\"WRITTEN\"}]}"))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value(201)).andExpect(jsonPath("$.data.contractId").value(12)).andExpect(jsonPath("$.data.archived").value(true))
   .andExpect(jsonPath("$.data.savedSchedules[0].scheduleId").value(51));
  when(contracts.listMyContracts(user,null,0,20)).thenReturn(new MyContractsInfo(
   List.of(new MyContractsInfo.Item(15L,"역삼동 원룸 월세 계약서","LEASE","임대차계약서","HOME",OffsetDateTime.parse("2026-11-12T14:20:00+09:00"),2,OffsetDateTime.parse("2026-11-05T10:00:00+09:00"),3,1,0)),
   Map.of("ALL",1,"HOME",1,"WORK",0),0,20,false));
  mvc.perform(get("/api/contracts").header("Authorization",auth())).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.items[0].contractId").value(15)).andExpect(jsonPath("$.data.categoryCounts.ALL").value(1));
  when(contracts.getMyContractDetail(user,15L)).thenReturn(new MyContractDetailInfo(
   15L,"역삼동 원룸 월세 계약서","LEASE","임대차계약서","A",
   List.of(new MyContractDetailInfo.VersionItem(2,88L,OffsetDateTime.parse("2026-11-12T14:20:00+09:00"),true)),
   new MyContractDetailInfo.Summary(1,0,11),
   List.of(new MyContractDetailInfo.KeyTermItem("보증금","1,000만 원","OK","WRITTEN")),
   List.of(new MyContractDetailInfo.ScheduleItem(51L,"입주·전입신고",LocalDate.of(2026,11,20),8))));
  mvc.perform(get("/api/contracts/15").header("Authorization",auth())).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.contractId").value(15)).andExpect(jsonPath("$.data.versions[0].analysisId").value(88))
   .andExpect(jsonPath("$.data.summary.totalClauseCount").value(11)).andExpect(jsonPath("$.data.schedules[0].dDay").value(8));
 }
 @Test void scheduleDetailUpdateCandidatesAndNotificationListMatchFrontendSpec() throws Exception {
  when(schedules.getDetail(user,51L)).thenReturn(new ScheduleDetailInfo(51L,"입주·전입신고",LocalDate.of(2026,11,20),8,"MANUAL","1쪽 제2조",
   new ScheduleDetailInfo.ContractRef(15L,"역삼동 원룸 월세 계약서",OffsetDateTime.parse("2026-11-05T10:00:00+09:00"))));
  mvc.perform(get("/api/schedules/51").header("Authorization",auth())).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.scheduleId").value(51)).andExpect(jsonPath("$.data.contract.contractId").value(15));
  when(schedules.update(eq(user),eq(51L),any())).thenReturn(new UpdateScheduleInfo(51L,"입주·전입신고",LocalDate.of(2026,11,27),15,"MANUAL",null));
  mvc.perform(patch("/api/schedules/51").header("Authorization",auth()).contentType("application/json").content("{\"date\":\"2026-11-27\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.date").value("2026-11-27")).andExpect(jsonPath("$.data.source").value("MANUAL"));
  when(schedules.getCandidates(user,7L)).thenReturn(new ScheduleCandidateSetInfo(12L,"카페 알바 근로계약서",
   List.of(new ScheduleCandidateSetInfo.CandidateItem(301L,"첫 출근",LocalDate.of(2026,12,1),"WRITTEN","1쪽 제1조",1,true,null)),
   List.of(new ScheduleCandidateSetInfo.MissingItem(306L,"갱신 통보 기한","계약서에 없어요."))));
  mvc.perform(get("/api/analyses/7/schedule-candidates").header("Authorization",auth())).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.contractId").value(12)).andExpect(jsonPath("$.data.candidates[0].candidateId").value(301))
   .andExpect(jsonPath("$.data.missing[0].candidateId").value(306));
  when(notifications.list(user,20,null)).thenReturn(new NotificationsInfo(1L,List.of(
   new NotificationsInfo.Item(3L,"SCHEDULE_DUE","D-8 · 입주·전입신고","역삼동 원룸 월세 계약서",new NotificationsInfo.Target(51L,null),false,OffsetDateTime.parse("2026-11-12T09:00:00+09:00")))));
  mvc.perform(get("/api/notifications").header("Authorization",auth())).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.unreadCount").value(1)).andExpect(jsonPath("$.data.items[0].target.scheduleId").value(51));
 }
 @Test void comparisonEndpointsMatchFrontendSpec() throws Exception {
  when(comparisons.preview(user,15L,21L)).thenReturn(new ComparisonPreviewInfo("FULL",null,null,null));
  mvc.perform(get("/api/comparisons/preview").param("previousContractId","15").param("revisedContractId","21").header("Authorization",auth()))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.compatibility").value("FULL"));
  ComparisonInfo compInfo=new ComparisonInfo(9L,"PENDING_CONFIRM","PARTIAL",new ComparisonInfo.Counts(1,0,0,1),
   List.of(new ComparisonInfo.ChangeItem(1L,"CHANGED","AMOUNT","월세","월 55만 원","월 58만 원","매달 3만 원 늘어요","WRITTEN")),
   List.of(new ComparisonInfo.UncomparedItem(5L,"제5조 관리비 포함 항목","수정본 2쪽 아래 칸","글자가 흐려서 읽지 못했어요.","관리비에는 인터넷 요금이 포함된다.")),
   "보증금 1,000만 원");
  when(comparisons.createWithStatus(eq(user),any())).thenReturn(new CreateComparisonResult(compInfo,true));
  mvc.perform(post("/api/comparisons").header("Authorization",auth()).contentType("application/json")
   .content("{\"previousContractId\":15,\"revisedContractId\":21}"))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.data.comparisonId").value(9));
  when(comparisons.createWithStatus(eq(user),any())).thenReturn(new CreateComparisonResult(compInfo,false));
  mvc.perform(post("/api/comparisons").header("Authorization",auth()).contentType("application/json")
   .content("{\"previousContractId\":15,\"revisedContractId\":21}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(200)).andExpect(jsonPath("$.data.comparisonId").value(9));
  when(comparisons.get(user,9L)).thenReturn(compInfo);
  mvc.perform(get("/api/comparisons/9").header("Authorization",auth())).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.uncompared[0].uncomparedId").value(5));
  when(comparisons.enterManualInput(eq(user),eq(9L),any())).thenReturn(new ComparisonManualInputInfo(5L,"CHANGED","CLAUSE","제5조 관리비 포함 항목","관리비에는 인터넷 요금이 포함된다.","수도 요금 포함","MANUAL"));
  mvc.perform(post("/api/comparisons/9/manual-inputs").header("Authorization",auth()).contentType("application/json")
   .content("{\"uncomparedId\":5,\"text\":\"수도 요금 포함\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.changeId").value(5)).andExpect(jsonPath("$.data.source").value("MANUAL"));
  when(comparisons.getScheduleChanges(user,9L)).thenReturn(new ComparisonScheduleChangesInfo("역삼동 원룸 월세 계약서",
   List.of(new ComparisonScheduleChangesInfo.ChangeItem(51L,null,"UPDATE","입주·전입신고",LocalDate.of(2026,11,20),LocalDate.of(2026,11,27),"WRITTEN",null))));
  mvc.perform(get("/api/comparisons/9/schedule-changes").header("Authorization",auth())).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.changes[0].action").value("UPDATE"));
  UUID key=UUID.randomUUID();
  when(comparisons.confirm(eq(user),eq(9L),eq(key),any())).thenReturn(new ConfirmComparisonInfo(15L,2,1,
   List.of(new ConfirmComparisonInfo.SavedSchedule(51L,"입주·전입신고",LocalDate.of(2026,11,27)))));
  mvc.perform(post("/api/comparisons/9/confirm").header("Authorization",auth()).header("Idempotency-Key",key.toString())
   .contentType("application/json").content("{\"schedules\":[]}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
  when(comparisons.discard(user,9L)).thenReturn(new DiscardComparisonInfo(9L,"DISCARDED"));
  mvc.perform(post("/api/comparisons/9/discard").header("Authorization",auth())).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.comparisonId").value(9)).andExpect(jsonPath("$.data.status").value("DISCARDED"));
  verify(comparisons).discard(user,9L);
 }
}
