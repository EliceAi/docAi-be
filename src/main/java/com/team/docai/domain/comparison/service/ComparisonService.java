package com.team.docai.domain.comparison.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.dto.*;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.chat.service.ChatService;
import com.team.docai.domain.comparison.dto.*;
import com.team.docai.domain.comparison.entity.*;
import com.team.docai.domain.comparison.repository.*;
import com.team.docai.domain.contract.dto.*;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.domain.schedule.entity.Schedule;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.global.error.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ComparisonService {
 private final ComparisonRepository comparisons;
 private final ComparisonChangeRepository changes;
 private final ContractService contracts;
 private final DocumentTypeService types;
 private final ObjectProvider<AnalysisService> analysisProvider;
 private final ObjectProvider<ScheduleService> scheduleProvider;
 private final ObjectProvider<ChatService> chatProvider;
 private final ObjectProvider<JdbcTemplate> jdbcProvider;
 private final ObjectMapper mapper;
 public ComparisonForChatInfo getForChat(UUID userId,Long comparisonId){
  if(userId==null||comparisonId==null)throw new BusinessException(ErrorCode.COMPARISON_NOT_FOUND);
  Comparison c=comparisons.findByComparisonIdAndUserId(comparisonId,userId).orElseThrow(()->new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));
  return new ComparisonForChatInfo(c.getComparisonId(),c.getPreviousContractId(),c.getStatus());
 }
 public ComparisonPreviewInfo preview(UUID userId,Long previousContractId,Long revisedContractId){
  if(userId==null||previousContractId==null||revisedContractId==null||previousContractId.equals(revisedContractId))
   throw new BusinessException(ErrorCode.INVALID_INPUT);
  var prevFor=contracts.getForAnalysis(userId,previousContractId);
  var revFor=contracts.getForAnalysis(userId,revisedContractId);
  var prevVer=contracts.getVersionOwner(userId,prevFor.versionId());
  var revVer=contracts.getVersionOwner(userId,revFor.versionId());
  var prevSum=contracts.getSummary(userId,previousContractId);
  var revSum=contracts.getSummary(userId,revisedContractId);
  var analysis=analysisProvider==null?null:analysisProvider.getIfAvailable();
  OffsetDateTime prevAt=prevSum.latestAnalysisId()!=null&&analysis!=null?analysis.getSummary(prevSum.latestAnalysisId()).map(AnalysisSummaryInfo::completedAt).orElse(null):null;
  OffsetDateTime revAt=revSum.latestAnalysisId()!=null&&analysis!=null?analysis.getSummary(revSum.latestAnalysisId()).map(AnalysisSummaryInfo::completedAt).orElse(null):null;
  String prevTypeName=types!=null?types.getName(prevFor.documentTypeCode()):prevFor.documentTypeCode();
  String revTypeName=types!=null?types.getName(revFor.documentTypeCode()):revFor.documentTypeCode();
  var prevDoc=new ComparisonPreviewInfo.DocSummary(previousContractId,prevVer.title()!=null?prevVer.title():prevTypeName,prevFor.documentTypeCode(),prevVer.versionNo(),prevVer.pageCount(),prevAt);
  var revDoc=new ComparisonPreviewInfo.DocSummary(revisedContractId,revVer.title()!=null?revVer.title():revTypeName,revFor.documentTypeCode(),revVer.versionNo(),revVer.pageCount(),revAt);
  if(!prevFor.documentTypeCode().equals(revFor.documentTypeCode())){
   return new ComparisonPreviewInfo("IMPOSSIBLE","두 문서의 계약 종류가 달라요 ("+prevTypeName+" · "+revTypeName+"). 같은 계약의 이전본과 수정본만 비교할 수 있어요.",prevDoc,revDoc);
  }
  if(prevSum.latestAnalysisId()==null||revSum.latestAnalysisId()==null||prevVer.pageCount()==0||revVer.pageCount()==0){
   return new ComparisonPreviewInfo("IMPOSSIBLE","비교할 원문 쪽 정보가 없어요. 문서를 다시 올리거나 다른 문서를 골라 주세요.",prevDoc,revDoc);
  }
  var prevSource=analysis==null?null:analysis.getComparisonSource(prevSum.latestAnalysisId()).orElse(null);
  var revSource=analysis==null?null:analysis.getComparisonSource(revSum.latestAnalysisId()).orElse(null);
  if(prevSource==null||revSource==null){
   return new ComparisonPreviewInfo("IMPOSSIBLE","분석이 완료된 두 계약서만 비교할 수 있어요.",prevDoc,revDoc);
  }
  if(prevVer.pageCount()!=revVer.pageCount()){
   int minP=Math.min(prevVer.pageCount(),revVer.pageCount());
   int diff=Math.abs(prevVer.pageCount()-revVer.pageCount());
   String particle=diff>1?"부터는":"은";
   String side=revVer.pageCount()>prevVer.pageCount()?"추가된 내용":"이전본에만 있는 내용";
   String reason="이전본은 "+prevVer.pageCount()+"쪽, 수정본은 "+revVer.pageCount()+"쪽이에요. 짝이 없는 "+(minP+1)+"쪽"+particle+" \""+side+"\"으로만 보여 드려요.";
   return new ComparisonPreviewInfo("PARTIAL",reason,prevDoc,revDoc);
  }
  if(!prevSource.partialPages().isEmpty()||!revSource.partialPages().isEmpty()||prevSource.readPages().size()<prevVer.pageCount()||revSource.readPages().size()<revVer.pageCount()){
   return new ComparisonPreviewInfo("PARTIAL","일부 쪽에서 읽지 못한 항목이 있어 읽은 항목만 먼저 비교해요.",prevDoc,revDoc);
  }
  return new ComparisonPreviewInfo("FULL",null,prevDoc,revDoc);
 }
 @Transactional
 public ComparisonInfo create(UUID userId,CreateComparisonCommand command){
  if(userId==null||command==null||command.previousContractId()==null||command.revisedContractId()==null||command.previousContractId().equals(command.revisedContractId()))
   throw new BusinessException(ErrorCode.INVALID_INPUT);
  Long prevId=command.previousContractId();Long revId=command.revisedContractId();
  var existing=comparisons.findByUserIdAndPreviousContractIdAndRevisedContractIdAndStatus(userId,prevId,revId,"PENDING_CONFIRM");
  if(existing.isPresent())return toInfo(existing.get(),changes.findByComparisonIdOrderByChangeIdAsc(existing.get().getComparisonId()));
  ComparisonPreviewInfo prev=preview(userId,prevId,revId);
  if("IMPOSSIBLE".equals(prev.compatibility()))throw new BusinessException(ErrorCode.COMPARISON_IMPOSSIBLE);
  var prevFor=contracts.getForAnalysis(userId,prevId);
  var revFor=contracts.getForAnalysis(userId,revId);
  var prevSum=contracts.getSummary(userId,prevId);
  var revSum=contracts.getSummary(userId,revId);
  var analysis=analysisProvider==null?null:analysisProvider.getIfAvailable();
  var prevSource=analysis==null?null:analysis.getComparisonSource(prevSum.latestAnalysisId()).orElseThrow(()->new BusinessException(ErrorCode.COMPARISON_IMPOSSIBLE));
  var revSource=analysis==null?null:analysis.getComparisonSource(revSum.latestAnalysisId()).orElseThrow(()->new BusinessException(ErrorCode.COMPARISON_IMPOSSIBLE));
  var built=buildDiff(prevSource,revSource);
  Comparison comp=comparisons.saveAndFlush(Comparison.create(userId,prevId,prevFor.versionId(),revId,revFor.versionId(),revSum.latestAnalysisId(),prev.compatibility(),built.unchangedSummary()));
  List<ComparisonChange> savedChanges=new ArrayList<>();
  for(var draft:built.changes()){
   if("UNCOMPARED".equals(draft.type())){
    savedChanges.add(changes.save(ComparisonChange.createUncompared(comp.getComparisonId(),draft.category(),draft.label(),draft.beforeText(),draft.location(),draft.reason())));
   }else{
    savedChanges.add(changes.save(ComparisonChange.createChange(comp.getComparisonId(),draft.type(),draft.category(),draft.label(),draft.beforeText(),draft.afterText(),draft.note(),draft.source())));
   }
  }
  changes.flush();
  return toInfo(comp,savedChanges);
 }
 public ComparisonInfo get(UUID userId,Long comparisonId){
  if(userId==null||comparisonId==null)throw new BusinessException(ErrorCode.COMPARISON_NOT_FOUND);
  Comparison comp=comparisons.findByComparisonIdAndUserId(comparisonId,userId).orElseThrow(()->new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));
  return toInfo(comp,changes.findByComparisonIdOrderByChangeIdAsc(comparisonId));
 }
 @Transactional
 public ComparisonInfo enterManualInput(UUID userId,Long comparisonId,ComparisonManualInputCommand command){
  if(userId==null||comparisonId==null||command==null||command.uncomparedId()==null||command.text()==null||command.text().isBlank())
   throw new BusinessException(ErrorCode.INVALID_INPUT);
  Comparison comp=comparisons.lockOwned(userId,comparisonId).orElseThrow(()->new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));
  if(!"PENDING_CONFIRM".equals(comp.getStatus()))throw new BusinessException(ErrorCode.COMPARISON_ALREADY_CLOSED);
  ComparisonChange ch=changes.findByChangeIdAndComparisonId(command.uncomparedId(),comparisonId).orElseThrow(()->new BusinessException(ErrorCode.UNCOMPARED_NOT_FOUND));
  boolean previousSide=ch.getLocation()!=null&&ch.getLocation().startsWith("이전본");
  ch.applyManualInput(command.text().strip(),previousSide);
  changes.saveAndFlush(ch);
  return toInfo(comp,changes.findByComparisonIdOrderByChangeIdAsc(comparisonId));
 }
 public ComparisonScheduleChangesInfo getScheduleChanges(UUID userId,Long comparisonId){
  if(userId==null||comparisonId==null)throw new BusinessException(ErrorCode.COMPARISON_NOT_FOUND);
  Comparison comp=comparisons.findByComparisonIdAndUserId(comparisonId,userId).orElseThrow(()->new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));
  var contractSum=contracts.getSummary(userId,comp.getPreviousContractId());
  var schedService=scheduleProvider==null?null:scheduleProvider.getIfAvailable();
  var analysis=analysisProvider==null?null:analysisProvider.getIfAvailable();
  List<Schedule> existing=schedService==null?List.of():schedService.findEntitiesForContract(userId,comp.getPreviousContractId());
  List<ScheduleCandidateInfo> candidates=analysis==null?List.of():analysis.getScheduleCandidates(comp.getRevisedAnalysisId()).stream().filter(c->c.date()!=null).toList();
  Set<Long> matchedCandidateIds=new HashSet<>();
  List<ComparisonScheduleChangesInfo.ChangeItem> items=new ArrayList<>();
  for(Schedule s:existing){
   ScheduleCandidateInfo match=candidates.stream()
    .filter(c->!matchedCandidateIds.contains(c.candidateId()))
    .filter(c->(s.getDateKind()!=null&&s.getDateKind().equals(c.dateKind()))||s.getTitle().equals(c.title()))
    .findFirst().orElse(null);
   if(match!=null){
    matchedCandidateIds.add(match.candidateId());
    if(!s.getDate().equals(match.date())){
     items.add(new ComparisonScheduleChangesInfo.ChangeItem(s.getScheduleId(),match.candidateId(),"UPDATE",s.getTitle(),s.getDate(),match.date(),match.source(),match.location()));
    }
   }else{
    items.add(new ComparisonScheduleChangesInfo.ChangeItem(s.getScheduleId(),null,"DELETE",s.getTitle(),s.getDate(),null,"MANUAL".equals(s.getSource().name())?"WRITTEN":s.getSource().name(),s.getLocation()));
   }
  }
  for(ScheduleCandidateInfo c:candidates){
   if(!matchedCandidateIds.contains(c.candidateId())){
    items.add(new ComparisonScheduleChangesInfo.ChangeItem(null,c.candidateId(),"CREATE",c.title(),null,c.date(),c.source(),c.location()));
   }
  }
  String title=contractSum.title()!=null?contractSum.title():"계약서";
  return new ComparisonScheduleChangesInfo(title,items);
 }
 @Transactional
 public ConfirmComparisonInfo confirm(UUID userId,Long comparisonId,UUID idempotencyKey,ConfirmComparisonCommand command){
  if(userId==null||comparisonId==null||idempotencyKey==null)throw new BusinessException(ErrorCode.INVALID_INPUT);
  var items=command==null||command.schedules()==null?List.<ConfirmComparisonCommand.ScheduleItem>of():command.schedules();
  String hash=confirmHash(comparisonId,items);
  var jdbc=jdbcProvider==null?null:jdbcProvider.getIfAvailable();
  if(jdbc!=null){
   jdbc.update("INSERT INTO idempotency_record(user_id,endpoint,idempotency_key,request_hash,status,created_at,updated_at) VALUES (?,'COMPARISON_CONFIRM',?,?,'IN_PROGRESS',now(),now()) ON CONFLICT DO NOTHING",userId,idempotencyKey.toString(),hash);
   var record=jdbc.queryForMap("SELECT request_hash,status,response_json::text AS response FROM idempotency_record WHERE user_id=? AND endpoint='COMPARISON_CONFIRM' AND idempotency_key=? FOR UPDATE",userId,idempotencyKey.toString());
   if(!hash.equals(record.get("request_hash")))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
   if("DONE".equals(record.get("status"))){try{return mapper.readValue((String)record.get("response"),ConfirmComparisonInfo.class);}catch(Exception e){throw new IllegalStateException(e);}}
  }
  Comparison initial=comparisons.findByComparisonIdAndUserId(comparisonId,userId).orElseThrow(()->new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));
  if(!"PENDING_CONFIRM".equals(initial.getStatus()))throw new BusinessException(ErrorCode.COMPARISON_ALREADY_CLOSED);
  Long prevId=initial.getPreviousContractId();
  Long revId=initial.getRevisedContractId();
  if(revId==null)throw new BusinessException(ErrorCode.COMPARISON_ALREADY_CLOSED);
  // Lock order: Contract (ascending ID) -> Comparison -> Analysis
  if(prevId<revId){contracts.lockForUpdate(prevId);contracts.lockForUpdate(revId);}
  else{contracts.lockForUpdate(revId);contracts.lockForUpdate(prevId);}
  Comparison comp=comparisons.lockOwned(userId,comparisonId).orElseThrow(()->new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));
  if(!"PENDING_CONFIRM".equals(comp.getStatus()))throw new BusinessException(ErrorCode.COMPARISON_ALREADY_CLOSED);
  int newVersion=contracts.mergeRevisedVersion(userId,prevId,revId,comp.getRevisedVersionId());
  var chat=chatProvider==null?null:chatProvider.getIfAvailable();
  if(chat!=null)chat.moveContract(revId,prevId);
  var analysis=analysisProvider==null?null:analysisProvider.getIfAvailable();
  if(analysis!=null)analysis.clearSimilarContract(comp.getRevisedVersionId());
  OffsetDateTime now=OffsetDateTime.now();
  comp.confirm(now);
  comparisons.saveAndFlush(comp);
  for(Comparison other:comparisons.findByPreviousContractIdAndStatus(prevId,"PENDING_CONFIRM")){
   if(!other.getComparisonId().equals(comparisonId)){
    other.supersede(now);
    comparisons.save(other);
   }
  }
  comparisons.flush();
  var schedService=scheduleProvider==null?null:scheduleProvider.getIfAvailable();
  List<ConfirmComparisonInfo.SavedSchedule> savedSchedules=schedService==null?List.of():
   schedService.applyComparisonSchedules(userId,prevId,comp.getRevisedAnalysisId(),items);
  ConfirmComparisonInfo info=new ConfirmComparisonInfo(prevId,newVersion,newVersion-1,savedSchedules);
  if(jdbc!=null){
   try{jdbc.update("UPDATE idempotency_record SET status='DONE',response_json=CAST(? AS jsonb),updated_at=now() WHERE user_id=? AND endpoint='COMPARISON_CONFIRM' AND idempotency_key=?",mapper.writeValueAsString(info),userId,idempotencyKey.toString());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
  }
  return info;
 }
 @Transactional
 public void discard(UUID userId,Long comparisonId){
  if(userId==null||comparisonId==null)throw new BusinessException(ErrorCode.COMPARISON_NOT_FOUND);
  Comparison initial=comparisons.findByComparisonIdAndUserId(comparisonId,userId).orElseThrow(()->new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));
  if("DISCARDED".equals(initial.getStatus()))return;
  if(!"PENDING_CONFIRM".equals(initial.getStatus()))throw new BusinessException(ErrorCode.COMPARISON_ALREADY_CLOSED);
  Long revId=initial.getRevisedContractId();
  if(revId!=null)contracts.lockForUpdate(revId);
  Comparison comp=comparisons.lockOwned(userId,comparisonId).orElseThrow(()->new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));
  if("DISCARDED".equals(comp.getStatus()))return;
  if(!"PENDING_CONFIRM".equals(comp.getStatus()))throw new BusinessException(ErrorCode.COMPARISON_ALREADY_CLOSED);
  comp.discard(OffsetDateTime.now());
  comparisons.saveAndFlush(comp);
  if(revId!=null)contracts.softDeleteIfTemporary(userId,revId);
 }
 private ComparisonInfo toInfo(Comparison comp,List<ComparisonChange> list){
  var changeItems=new ArrayList<ComparisonInfo.ChangeItem>();
  var uncomparedItems=new ArrayList<ComparisonInfo.UncomparedItem>();
  int changedCount=0;int addedCount=0;int manualCount=0;
  for(ComparisonChange ch:list){
   if("UNCOMPARED".equals(ch.getType())){
    uncomparedItems.add(new ComparisonInfo.UncomparedItem(ch.getChangeId(),ch.getLabel(),ch.getLocation()!=null?ch.getLocation():"수정본 1쪽",ch.getReason()!=null?ch.getReason():"글자가 흐려서 읽지 못했어요.",ch.getBeforeText()!=null?ch.getBeforeText():""));
   }else{
    if("ADDED".equals(ch.getType()))addedCount++;else changedCount++;
    if("MANUAL".equals(ch.getSource()))manualCount++;
    changeItems.add(new ComparisonInfo.ChangeItem(ch.getChangeId(),ch.getType(),ch.getCategory(),ch.getLabel(),ch.getBeforeText(),ch.getAfterText(),ch.getNote(),ch.getSource()));
   }
  }
  return new ComparisonInfo(comp.getComparisonId(),comp.getStatus(),comp.getCompatibility(),
   new ComparisonInfo.Counts(changedCount,addedCount,manualCount,uncomparedItems.size()),changeItems,uncomparedItems,comp.getUnchangedSummary()!=null?comp.getUnchangedSummary():"");
 }
 private record DraftChange(String type,String category,String label,String beforeText,String afterText,String note,String source,String location,String reason){}
 private record BuiltDiff(List<DraftChange> changes,String unchangedSummary){}
 private BuiltDiff buildDiff(ComparisonSourceInfo prev,ComparisonSourceInfo rev){
  List<DraftChange> list=new ArrayList<>();
  List<String> unchanged=new ArrayList<>();
  Map<String,ComparisonSourceInfo.KeyTerm> prevTerms=prev.keyTerms().stream().collect(Collectors.toMap(ComparisonSourceInfo.KeyTerm::label,Function.identity(),(a,b)->a,LinkedHashMap::new));
  Map<String,ComparisonSourceInfo.KeyTerm> revTerms=rev.keyTerms().stream().collect(Collectors.toMap(ComparisonSourceInfo.KeyTerm::label,Function.identity(),(a,b)->a,LinkedHashMap::new));
  Set<String> allLabels=new LinkedHashSet<>(prevTerms.keySet());allLabels.addAll(revTerms.keySet());
  for(String label:allLabels){
   var pt=prevTerms.get(label);var rt=revTerms.get(label);
   if(pt!=null&&"MASKED".equals(pt.state())||rt!=null&&"MASKED".equals(rt.state()))continue;
   if(rt!=null&&"UNREADABLE".equals(rt.state())){
    list.add(new DraftChange("UNCOMPARED",categoryForLabel(label),label,pt!=null&&pt.value()!=null?pt.value():"", "수정본 1쪽","글자가 흐려서 읽지 못했어요.", "WRITTEN","수정본 1쪽","글자가 흐려서 읽지 못했어요."));
    continue;
   }
   if(pt!=null&&"UNREADABLE".equals(pt.state())){
    list.add(new DraftChange("UNCOMPARED",categoryForLabel(label),label,rt!=null&&rt.value()!=null?rt.value():"", "이전본 1쪽","글자가 흐려서 읽지 못했어요.", "WRITTEN","이전본 1쪽","글자가 흐려서 읽지 못했어요."));
    continue;
   }
   String pv=pt==null||"MISSING".equals(pt.state())?null:pt.value();
   String rv=rt==null||"MISSING".equals(rt.state())?null:rt.value();
   if(Objects.equals(pv,rv)){
    if(rv!=null)unchanged.add(label+" "+rv);
   }else if(pv==null&&rv!=null){
    list.add(new DraftChange("ADDED",categoryForLabel(label),label,null,rv,"새로 추가된 조건이에요","WRITTEN",null,null));
   }else if(pv!=null&&rv==null){
    list.add(new DraftChange("REMOVED",categoryForLabel(label),label,pv,null,"수정본에서 빠진 조건이에요","WRITTEN",null,null));
   }else{
    list.add(new DraftChange("CHANGED",categoryForLabel(label),label,pv,rv,label+" 내용이 바뀌었어요","WRITTEN",null,null));
   }
  }
  Map<String,ComparisonSourceInfo.Clause> prevClauses=new LinkedHashMap<>();
  for(var c:prev.clauses())prevClauses.putIfAbsent(clauseKey(c),c);
  Map<String,ComparisonSourceInfo.Clause> revClauses=new LinkedHashMap<>();
  for(var c:rev.clauses())revClauses.putIfAbsent(clauseKey(c),c);
  Set<String> allClauseKeys=new LinkedHashSet<>(prevClauses.keySet());allClauseKeys.addAll(revClauses.keySet());
  for(String key:allClauseKeys){
   var pc=prevClauses.get(key);var rc=revClauses.get(key);
   if(rc!=null&&"UNREADABLE".equals(rc.verdict())){
    String prevText=pc!=null&&pc.originalText()!=null?pc.originalText():"";
    String loc="수정본 "+rc.pageNo()+"쪽";
    list.add(new DraftChange("UNCOMPARED","CLAUSE",key,prevText,null,null,"WRITTEN",loc,"글자가 흐려서 읽지 못했어요."));
    continue;
   }
   if(pc!=null&&"UNREADABLE".equals(pc.verdict())){
    String revText=rc!=null&&rc.originalText()!=null?rc.originalText():"";
    String loc="이전본 "+pc.pageNo()+"쪽";
    list.add(new DraftChange("UNCOMPARED","CLAUSE",key,revText,null,null,"WRITTEN",loc,"글자가 흐려서 읽지 못했어요."));
    continue;
   }
   if(pc==null&&rc!=null){
    list.add(new DraftChange("ADDED","CLAUSE",key,null,rc.originalText(),"새로 추가된 조항이에요","WRITTEN",null,null));
   }else if(pc!=null&&rc==null){
    list.add(new DraftChange("REMOVED","CLAUSE",key,pc.originalText(),null,"수정본에서 빠진 조항이에요","WRITTEN",null,null));
   }else if(pc!=null&&rc!=null&&!Objects.equals(pc.originalText(),rc.originalText())){
    list.add(new DraftChange("CHANGED","CLAUSE",key,pc.originalText(),rc.originalText(),"조항 문구가 바뀌었어요","WRITTEN",null,null));
   }
  }
  String summary=unchanged.stream().limit(4).collect(Collectors.joining(" · "));
  if(summary.length()>300)summary=summary.substring(0,300);
  return new BuiltDiff(list,summary);
 }
 private static String clauseKey(ComparisonSourceInfo.Clause c){
  if(c.articleNo()!=null&&!c.articleNo().isBlank()&&c.title()!=null&&!c.title().isBlank())return c.articleNo()+" "+c.title();
  if(c.title()!=null&&!c.title().isBlank())return c.title();
  if(c.articleNo()!=null&&!c.articleNo().isBlank())return c.articleNo();
  return "조항 "+c.clauseId();
 }
 private static String categoryForLabel(String label){
  if(label==null)return "CLAUSE";
  if(label.contains("임금")||label.contains("급여")||label.contains("보증금")||label.contains("월세")||label.contains("차임")||label.contains("관리비")||label.contains("수당"))return "AMOUNT";
  if(label.contains("기간")||label.contains("일")||label.contains("기한")||label.contains("날짜"))return "DATE";
  return "CLAUSE";
 }
 private String confirmHash(Long comparisonId,List<ConfirmComparisonCommand.ScheduleItem> items){
  try{
   MessageDigest digest=MessageDigest.getInstance("SHA-256");
   digest.update(ByteBuffer.allocate(8).putLong(comparisonId).array());
   for(var s:items){
    String line=s.action()+"|"+(s.scheduleId()==null?"null":s.scheduleId())+"|"+(s.candidateId()==null?"null":s.candidateId())+"|"+(s.title()==null?"":s.title())+"|"+(s.date()==null?"":s.date())+"\n";
    digest.update(line.getBytes(StandardCharsets.UTF_8));
   }
   return HexFormat.of().formatHex(digest.digest());
  }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
}
