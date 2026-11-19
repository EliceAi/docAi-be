package com.team.docai.domain.comparison;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.client.*;
import com.team.docai.domain.analysis.dto.*;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.chat.service.ChatService;
import com.team.docai.domain.comparison.dto.*;
import com.team.docai.domain.comparison.entity.*;
import com.team.docai.domain.comparison.repository.*;
import com.team.docai.domain.comparison.service.ComparisonService;
import com.team.docai.domain.contract.dto.*;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.domain.schedule.entity.*;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.global.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComparisonServiceTest {
 final UUID user=UUID.randomUUID();
 final ComparisonRepository comparisons=mock(ComparisonRepository.class);
 final ComparisonChangeRepository changes=mock(ComparisonChangeRepository.class);
 final ContractService contracts=mock(ContractService.class);
 final DocumentTypeService types=mock(DocumentTypeService.class);
 final AnalysisService analyses=mock(AnalysisService.class);
 final ScheduleService schedules=mock(ScheduleService.class);
 final ChatService chats=mock(ChatService.class);
 final GptClient gpt=mock(GptClient.class);
 final ObjectProvider<AnalysisService> analysisProvider=mock(ObjectProvider.class);
 final ObjectProvider<ScheduleService> scheduleProvider=mock(ObjectProvider.class);
 final ObjectProvider<ChatService> chatProvider=mock(ObjectProvider.class);
 final ObjectProvider<GptClient> gptProvider=mock(ObjectProvider.class);
 final ComparisonService service=new ComparisonService(comparisons,changes,contracts,types,analysisProvider,scheduleProvider,chatProvider,null,gptProvider,null,new ObjectMapper());

 @Test void previewChecksPreconditionsAndTypeAndPages(){
  when(analysisProvider.getIfAvailable()).thenReturn(analyses);
  when(types.getName("LEASE")).thenReturn("임대차계약서");
  when(types.getName("EMPLOYMENT")).thenReturn("근로계약서");

  when(contracts.getForAnalysis(user,10L)).thenReturn(new ContractForAnalysisInfo(10L,100L,"LEASE",false,List.of()));
  when(contracts.getForAnalysis(user,20L)).thenReturn(new ContractForAnalysisInfo(20L,200L,"LEASE",false,List.of()));
  assertEquals(ErrorCode.PREVIOUS_NOT_ARCHIVED,assertThrows(BusinessException.class,()->service.preview(user,10L,20L)).getErrorCode());

  when(contracts.getForAnalysis(user,10L)).thenReturn(new ContractForAnalysisInfo(10L,100L,"LEASE",true,List.of()));
  when(contracts.getForAnalysis(user,20L)).thenReturn(new ContractForAnalysisInfo(20L,200L,"LEASE",true,List.of()));
  assertEquals(ErrorCode.REVISED_ALREADY_ARCHIVED,assertThrows(BusinessException.class,()->service.preview(user,10L,20L)).getErrorCode());

  when(contracts.getForAnalysis(user,20L)).thenReturn(new ContractForAnalysisInfo(20L,200L,"EMPLOYMENT",false,List.of()));
  when(contracts.getSummary(user,10L)).thenReturn(new ContractSummaryInfo(10L,"원룸",true,null));
  when(contracts.getSummary(user,20L)).thenReturn(new ContractSummaryInfo(20L,"알바",false,2000L));
  assertEquals(ErrorCode.CONTRACT_NOT_ANALYZED,assertThrows(BusinessException.class,()->service.preview(user,10L,20L)).getErrorCode());

  when(contracts.getSummary(user,10L)).thenReturn(new ContractSummaryInfo(10L,"원룸",true,1000L));
  when(contracts.getVersionOwner(user,100L)).thenReturn(new ContractVersionInfo(10L,1,2,"원룸",0));
  when(contracts.getVersionOwner(user,200L)).thenReturn(new ContractVersionInfo(20L,1,2,"알바",0));
  when(analyses.getComparisonSource(1000L)).thenReturn(Optional.of(new ComparisonSourceInfo(1000L,"LEASE",2,List.of(1,2),List.of(),List.of(),List.of())));
  when(analyses.getComparisonSource(2000L)).thenReturn(Optional.of(new ComparisonSourceInfo(2000L,"EMPLOYMENT",2,List.of(1,2),List.of(),List.of(),List.of())));
  var diffType=service.preview(user,10L,20L);
  assertEquals("IMPOSSIBLE",diffType.compatibility());

  when(contracts.getForAnalysis(user,20L)).thenReturn(new ContractForAnalysisInfo(20L,200L,"LEASE",false,List.of()));
  when(contracts.getVersionOwner(user,200L)).thenReturn(new ContractVersionInfo(20L,1,3,"원룸 수정",0));
  when(analyses.getComparisonSource(2000L)).thenReturn(Optional.of(new ComparisonSourceInfo(2000L,"LEASE",3,List.of(1,2,3),List.of(),List.of(),List.of())));
  var diffPages=service.preview(user,10L,20L);
  assertEquals("PARTIAL",diffPages.compatibility());
  assertTrue(diffPages.reason().contains("추가된 내용"));

  when(contracts.getVersionOwner(user,200L)).thenReturn(new ContractVersionInfo(20L,1,2,"원룸 수정",0));
  var full=service.preview(user,10L,20L);
  assertEquals("FULL",full.compatibility());
  assertNull(full.reason());
 }

 @Test void createUsesGptForClauseNotesReusesValidAndSupersedesStale(){
  when(analysisProvider.getIfAvailable()).thenReturn(analyses);
  when(gptProvider.getIfAvailable()).thenReturn(gpt);
  when(types.getName("LEASE")).thenReturn("임대차계약서");
  when(contracts.getForAnalysis(user,10L)).thenReturn(new ContractForAnalysisInfo(10L,100L,"LEASE",true,List.of()));
  when(contracts.getForAnalysis(user,20L)).thenReturn(new ContractForAnalysisInfo(20L,200L,"LEASE",false,List.of()));
  when(contracts.getVersionOwner(user,100L)).thenReturn(new ContractVersionInfo(10L,1,2,"원룸",0));
  when(contracts.getVersionOwner(user,200L)).thenReturn(new ContractVersionInfo(20L,1,2,"원룸 수정",0));
  when(contracts.getSummary(user,10L)).thenReturn(new ContractSummaryInfo(10L,"원룸",true,1000L));
  when(contracts.getSummary(user,20L)).thenReturn(new ContractSummaryInfo(20L,"원룸 수정",false,2000L));
  when(analyses.getComparisonSource(1000L)).thenReturn(Optional.of(new ComparisonSourceInfo(1000L,"LEASE",2,List.of(1,2),List.of(),
   List.of(new ComparisonSourceInfo.KeyTerm("월세","월 55만 원",null,"OK"),new ComparisonSourceInfo.KeyTerm("보증금","1,000만 원",null,"OK")),
   List.of(new ComparisonSourceInfo.Clause(1L,"제5조","관리비","인터넷 포함",2,"OK")))));
  when(analyses.getComparisonSource(2000L)).thenReturn(Optional.of(new ComparisonSourceInfo(2000L,"LEASE",2,List.of(1,2),List.of(2),
   List.of(new ComparisonSourceInfo.KeyTerm("월세","월 58만 원",null,"OK"),new ComparisonSourceInfo.KeyTerm("보증금","1,000만 원",null,"OK")),
   List.of(new ComparisonSourceInfo.Clause(2L,"제5조","관리비",null,2,"UNREADABLE"),new ComparisonSourceInfo.Clause(3L,"특약 3",null,"반려동물 금지",2,"OK")))));
  when(gpt.call(eq(GptPurpose.COMPARE),any(),eq(ComparisonService.ClauseNotesResponse.class),any()))
   .thenReturn(new ComparisonService.ClauseNotesResponse(List.of(new ComparisonService.ClauseNotesResponse.ClauseNoteItem("특약 3","새로 생긴 의무예요"))));

  when(comparisons.saveAndFlush(any())).thenAnswer(inv->{
   Comparison c=inv.getArgument(0);if(c.getComparisonId()==null)ReflectionTestUtils.setField(c,"comparisonId",9L);return c;
  });
  List<ComparisonChange> stored=new ArrayList<>();
  long[] seq={1L};
  when(changes.save(any())).thenAnswer(inv->{
   ComparisonChange ch=inv.getArgument(0);ReflectionTestUtils.setField(ch,"changeId",seq[0]++);stored.add(ch);return ch;
  });

  var createdRes=service.createWithStatus(user,new CreateComparisonCommand(10L,20L));
  assertTrue(createdRes.created());
  var created=createdRes.comparison();
  assertEquals(9L,created.comparisonId());
  assertEquals(1,created.counts().changed());
  assertEquals(1,created.counts().added());
  assertEquals(1,created.counts().uncompared());
  assertEquals("새로 생긴 의무예요",created.changes().stream().filter(c->"특약 3".equals(c.label())).findFirst().orElseThrow().note());

  // Valid existing comparison is reused (created=false)
  Comparison comp=Comparison.create(user,10L,100L,20L,200L,2000L,"PARTIAL","보증금 1,000만 원");
  ReflectionTestUtils.setField(comp,"comparisonId",9L);
  when(comparisons.findByUserIdAndPreviousContractIdAndRevisedContractIdAndStatus(user,10L,20L,"PENDING_CONFIRM")).thenReturn(Optional.of(comp));
  when(changes.findByComparisonIdOrderByChangeIdAsc(9L)).thenReturn(stored);
  var reused=service.createWithStatus(user,new CreateComparisonCommand(10L,20L));
  assertFalse(reused.created());
  assertEquals(9L,reused.comparison().comparisonId());

  // Manual input only allows UNCOMPARED or MANUAL items, returns single item
  when(comparisons.lockOwned(user,9L)).thenReturn(Optional.of(comp));
  ComparisonChange uncompared=stored.stream().filter(c->"UNCOMPARED".equals(c.getType())).findFirst().orElseThrow();
  ComparisonChange normalChange=stored.stream().filter(c->!"UNCOMPARED".equals(c.getType())).findFirst().orElseThrow();
  when(changes.findByChangeIdAndComparisonId(uncompared.getChangeId(),9L)).thenReturn(Optional.of(uncompared));
  when(changes.findByChangeIdAndComparisonId(normalChange.getChangeId(),9L)).thenReturn(Optional.of(normalChange));

  assertEquals(ErrorCode.UNCOMPARED_NOT_FOUND,assertThrows(BusinessException.class,()->service.enterManualInput(user,9L,new ComparisonManualInputCommand(normalChange.getChangeId(),"불가"))).getErrorCode());

  var manualRes=service.enterManualInput(user,9L,new ComparisonManualInputCommand(uncompared.getChangeId(),"수도 요금 포함"));
  assertEquals(uncompared.getChangeId(),manualRes.changeId());
  assertEquals("CHANGED",manualRes.type());
  assertEquals("MANUAL",manualRes.source());
  assertEquals("수도 요금 포함",manualRes.after());

  // Re-entering manual input on same item is allowed before confirm
  var manualRes2=service.enterManualInput(user,9L,new ComparisonManualInputCommand(uncompared.getChangeId(),"인터넷·수도 요금 포함"));
  assertEquals("인터넷·수도 요금 포함",manualRes2.after());
 }

 @Test void confirmAndDiscardValidateStateAndCloseRelatedComparisons(){
  when(analysisProvider.getIfAvailable()).thenReturn(analyses);
  when(scheduleProvider.getIfAvailable()).thenReturn(schedules);
  when(chatProvider.getIfAvailable()).thenReturn(chats);
  when(contracts.getForAnalysis(user,10L)).thenReturn(new ContractForAnalysisInfo(10L,100L,"LEASE",true,List.of()));
  when(contracts.getForAnalysis(user,20L)).thenReturn(new ContractForAnalysisInfo(20L,200L,"LEASE",false,List.of()));
  when(contracts.getSummary(user,20L)).thenReturn(new ContractSummaryInfo(20L,"원룸 수정",false,2000L));

  Comparison comp=Comparison.create(user,10L,100L,20L,200L,2000L,"FULL","");
  ReflectionTestUtils.setField(comp,"comparisonId",9L);
  Comparison sibling=Comparison.create(user,11L,110L,20L,200L,2000L,"FULL","");
  ReflectionTestUtils.setField(sibling,"comparisonId",10L);

  when(comparisons.findByComparisonIdAndUserId(9L,user)).thenReturn(Optional.of(comp));
  when(comparisons.lockOwned(user,9L)).thenReturn(Optional.of(comp));
  when(comparisons.findByRevisedContractIdAndStatus(20L,"PENDING_CONFIRM")).thenReturn(List.of(sibling));
  when(contracts.mergeRevisedVersion(user,10L,20L,200L)).thenReturn(2);
  when(schedules.applyComparisonSchedules(eq(user),eq(10L),eq(2000L),any())).thenReturn(List.of(new ConfirmComparisonInfo.SavedSchedule(51L,"입주",LocalDate.of(2026,11,27))));

  var result=service.confirm(user,9L,UUID.randomUUID(),new ConfirmComparisonCommand(List.of()));
  assertEquals(10L,result.contractId());
  assertEquals(2,result.version());
  assertEquals(1,result.previousVersion());
  assertEquals("CONFIRMED",comp.getStatus());
  assertNull(comp.getRevisedContractId());
  assertEquals("SUPERSEDED",sibling.getStatus());

  // Already confirmed comparison rejects discard with COMPARISON_ALREADY_CLOSED
  assertEquals(ErrorCode.COMPARISON_ALREADY_CLOSED,assertThrows(BusinessException.class,()->service.discard(user,9L)).getErrorCode());
 }
}
