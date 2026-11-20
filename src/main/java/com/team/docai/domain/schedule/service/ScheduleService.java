package com.team.docai.domain.schedule.service;
import com.team.docai.domain.analysis.dto.*;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.comparison.dto.*;
import com.team.docai.domain.contract.dto.*;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.notification.service.NotificationService;
import com.team.docai.domain.schedule.dto.*;
import com.team.docai.domain.schedule.entity.*;
import com.team.docai.domain.schedule.repository.ScheduleRepository;
import com.team.docai.global.error.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
@Service @Transactional(readOnly=true)
public class ScheduleService {
 private final ScheduleRepository schedules;
 private final ContractService contracts;
 private final Clock clock;
 private final ObjectProvider<AnalysisService> analysisProvider;
 private final ObjectProvider<NotificationService> notificationProvider;
 public ScheduleService(ScheduleRepository schedules,ContractService contracts,Clock clock){
  this(schedules,contracts,clock,null,null);
 }
 @Autowired
 public ScheduleService(ScheduleRepository schedules,ContractService contracts,Clock clock,ObjectProvider<AnalysisService> analysisProvider,ObjectProvider<NotificationService> notificationProvider){
  this.schedules=schedules;this.contracts=contracts;this.clock=clock;this.analysisProvider=analysisProvider;this.notificationProvider=notificationProvider;
 }
 public int countForContract(UUID userId,Long contractId){return Math.toIntExact(schedules.countByUserIdAndContractId(userId,contractId));}
 public List<SavedScheduleInfo> listForContract(UUID userId,Long contractId){
  var today=seoulToday();
  return schedules.findByUserIdAndContractIdOrderByDateAscScheduleIdAsc(userId,contractId).stream()
   .map(s->new SavedScheduleInfo(s.getScheduleId(),s.getTitle(),s.getDate(),ChronoUnit.DAYS.between(today,s.getDate()))).toList();
 }
 public List<Schedule> findEntitiesForContract(UUID userId,Long contractId){
  return schedules.findByUserIdAndContractIdOrderByDateAscScheduleIdAsc(userId,contractId);
 }
 public List<ScheduleDueTargetInfo> findDueTargets(Collection<LocalDate> dates){
  if(dates==null||dates.isEmpty())return List.of();
  return schedules.findByDateInOrderByDateAscScheduleIdAsc(dates).stream()
   .map(s->new ScheduleDueTargetInfo(s.getScheduleId(),s.getUserId(),s.getContractId(),s.getTitle(),s.getDate())).toList();
 }
 public List<UpcomingScheduleInfo> upcoming(UUID userId,int limit){
  if(limit<1||limit>10)throw new BusinessException(ErrorCode.INVALID_INPUT);
  var today=seoulToday();
  return schedules.findByUserIdAndDateGreaterThanEqualOrderByDateAscScheduleIdAsc(userId,today,PageRequest.of(0,limit)).stream().map(s->{
   Long contractId=s.getContractId();String title=null;
   if(contractId!=null){try{title=contracts.getSummary(userId,contractId).title();}catch(BusinessException e){
    if(e.getErrorCode()!=ErrorCode.CONTRACT_NOT_FOUND)throw e;contractId=null;
   }}
   return new UpcomingScheduleInfo(s.getScheduleId(),s.getTitle(),s.getDate(),ChronoUnit.DAYS.between(today,s.getDate()),contractId,title);
  }).toList();
 }
 public ScheduleDetailInfo getDetail(UUID userId,Long scheduleId){
  if(userId==null||scheduleId==null)throw new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND);
  var s=schedules.findByScheduleIdAndUserId(scheduleId,userId).orElseThrow(()->new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
  var today=seoulToday();
  ScheduleDetailInfo.ContractRef ref=null;
  if(s.getContractId()!=null){
   try{
    ContractSummaryInfo summary=contracts.getSummary(userId,s.getContractId());
    OffsetDateTime analyzedAt=null;
    var analysis=analysisProvider==null?null:analysisProvider.getIfAvailable();
    if(analysis!=null&&summary.latestAnalysisId()!=null){
     analyzedAt=analysis.getSummary(summary.latestAnalysisId()).map(AnalysisSummaryInfo::completedAt).orElse(null);
    }
    ref=new ScheduleDetailInfo.ContractRef(summary.contractId(),summary.title(),analyzedAt);
   }catch(BusinessException e){
    if(e.getErrorCode()!=ErrorCode.CONTRACT_NOT_FOUND)throw e;
   }
  }
  return new ScheduleDetailInfo(s.getScheduleId(),s.getTitle(),s.getDate(),ChronoUnit.DAYS.between(today,s.getDate()),s.getSource().name(),s.getLocation(),ref);
 }
 @Transactional
 public UpdateScheduleInfo update(UUID userId,Long scheduleId,UpdateScheduleCommand command){
  if(userId==null||scheduleId==null||command==null||(command.title()==null&&command.date()==null))throw new BusinessException(ErrorCode.INVALID_INPUT);
  var s=schedules.lockOwned(userId,scheduleId).orElseThrow(()->new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
  if(command.title()!=null){
   String trimmed=command.title().strip();
   if(trimmed.isEmpty()||trimmed.codePointCount(0,trimmed.length())>50)throw new BusinessException(ErrorCode.INVALID_INPUT);
   s.updateTitle(trimmed);
  }
  if(command.date()!=null){
   LocalDate oldDate=s.getDate();
   s.updateDate(command.date());
   if(!command.date().equals(oldDate)){
    var notifications=notificationProvider==null?null:notificationProvider.getIfAvailable();
    if(notifications!=null)notifications.deleteUnreadForScheduleDate(s.getScheduleId(),oldDate);
   }
  }
  schedules.saveAndFlush(s);
  var today=seoulToday();
  return new UpdateScheduleInfo(s.getScheduleId(),s.getTitle(),s.getDate(),ChronoUnit.DAYS.between(today,s.getDate()),s.getSource().name(),s.getLocation());
 }
 @Transactional public void delete(UUID userId,Long scheduleId){
  var schedule=schedules.lockOwned(userId,scheduleId).orElseThrow(()->new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
  schedules.delete(schedule); // Existing FK deletes notifications for this schedule only.
 }
 public ScheduleCandidateSetInfo getCandidates(UUID userId,Long analysisId){
  if(userId==null||analysisId==null)throw new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND);
  var analysis=analysisProvider==null?null:analysisProvider.getIfAvailable();
  if(analysis==null)throw new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND);
  AnalysisRefInfo ref=analysis.getRef(userId,analysisId);
  if(!"COMPLETED".equals(ref.status()))throw new BusinessException(ErrorCode.CONTRACT_NOT_ANALYZED);
  Long contractId=ref.contractId();
  String contractTitle=contracts.getSummary(userId,contractId).title();
  List<ScheduleCandidateInfo> all=analysis.getScheduleCandidates(analysisId);
  var candidates=new ArrayList<ScheduleCandidateSetInfo.CandidateItem>();
  var missing=new ArrayList<ScheduleCandidateSetInfo.MissingItem>();
  for(var c:all){
   if(c.date()!=null){
    candidates.add(new ScheduleCandidateSetInfo.CandidateItem(c.candidateId(),c.title(),c.date(),c.source(),c.location(),c.pageNo(),c.defaultSelected(),c.conflictGroup()));
   }else{
    missing.add(new ScheduleCandidateSetInfo.MissingItem(c.candidateId(),c.title(),"계약서에 없어요."));
   }
  }
  return new ScheduleCandidateSetInfo(contractId,contractTitle,candidates,missing);
 }
 @Transactional
 public List<ArchiveContractInfo.SavedSchedule> saveForArchive(UUID userId,Long contractId,Long analysisId,List<ArchiveContractCommand.ScheduleItem> items){
  if(items==null||items.isEmpty())return List.of();
  var analysis=analysisProvider==null?null:analysisProvider.getIfAvailable();
  Map<Long,ScheduleCandidateInfo> candidateMap=analysis==null||analysisId==null?Map.of():
   analysis.getScheduleCandidates(analysisId).stream().collect(Collectors.toMap(ScheduleCandidateInfo::candidateId,Function.identity(),(a,b)->a));
  Set<Long> seenCandidates=new HashSet<>();
  Set<String> seenConflictGroups=new HashSet<>();
  for(var item:items){
   if(item==null||item.title()==null||item.title().isBlank()||item.title().codePointCount(0,item.title().length())>50||item.date()==null)
    throw new BusinessException(ErrorCode.INVALID_INPUT);
   Long candidateId=item.candidateId();
   if(candidateId!=null){
    if(!seenCandidates.add(candidateId))throw new BusinessException(ErrorCode.INVALID_INPUT);
    ScheduleCandidateInfo cand=candidateMap.get(candidateId);
    if(cand==null)throw new BusinessException(ErrorCode.CANDIDATE_NOT_FOUND);
    if(cand.conflictGroup()!=null&&!cand.conflictGroup().isBlank()&&!seenConflictGroups.add(cand.conflictGroup())){
     throw new BusinessException(ErrorCode.DATE_CONFLICT_UNRESOLVED);
    }
   }
  }
  var result=new ArrayList<ArchiveContractInfo.SavedSchedule>();
  for(var item:items){
   Long candidateId=item.candidateId();
   String dateKind=null;String location=null;ScheduleSource source;
   if(candidateId!=null){
    var existing=schedules.findByCandidateId(candidateId);
    if(existing.isPresent()){
     var s=existing.get();
     result.add(new ArchiveContractInfo.SavedSchedule(s.getScheduleId(),s.getTitle(),s.getDate()));
     continue;
    }
    ScheduleCandidateInfo cand=candidateMap.get(candidateId);
    dateKind=cand.dateKind();
    if(cand.date()!=null&&cand.date().equals(item.date())&&cand.title().equals(item.title().strip())){
     source="REVIEW".equals(cand.source())?ScheduleSource.REVIEW:ScheduleSource.WRITTEN;
     location=cand.location();
    }else{
     source=ScheduleSource.MANUAL;
     location=null;
    }
   }else{
    source=ScheduleSource.MANUAL;
   }
   Schedule saved=schedules.saveAndFlush(Schedule.create(userId,contractId,candidateId,dateKind,item.title().strip(),item.date(),source,location));
   result.add(new ArchiveContractInfo.SavedSchedule(saved.getScheduleId(),saved.getTitle(),saved.getDate()));
  }
  return result;
 }
 @Transactional
 public List<ConfirmComparisonInfo.SavedSchedule> applyComparisonSchedules(UUID userId,Long contractId,Long revisedAnalysisId,List<ConfirmComparisonCommand.ScheduleItem> items){
  if(items!=null){
   var analysis=analysisProvider==null?null:analysisProvider.getIfAvailable();
   Map<Long,ScheduleCandidateInfo> candidateMap=analysis==null||revisedAnalysisId==null?Map.of():
    analysis.getScheduleCandidates(revisedAnalysisId).stream().collect(Collectors.toMap(ScheduleCandidateInfo::candidateId,Function.identity(),(a,b)->a));
   Set<Long> seenCandidates=new HashSet<>();
   Set<String> seenConflictGroups=new HashSet<>();
   for(var item:items){
    if(item==null||item.action()==null)throw new BusinessException(ErrorCode.INVALID_INPUT);
    Long candId=item.candidateId();
    if(candId!=null){
     if(!seenCandidates.add(candId))throw new BusinessException(ErrorCode.INVALID_INPUT);
     ScheduleCandidateInfo cand=candidateMap.get(candId);
     if(cand==null)throw new BusinessException(ErrorCode.CANDIDATE_NOT_FOUND);
     if(cand.conflictGroup()!=null&&!cand.conflictGroup().isBlank()&&!seenConflictGroups.add(cand.conflictGroup())){
      throw new BusinessException(ErrorCode.DATE_CONFLICT_UNRESOLVED);
     }
     if("CREATE".equals(item.action())&&schedules.findByCandidateId(candId).isPresent()){
      throw new BusinessException(ErrorCode.INVALID_INPUT);
     }
    }
   }
   var notifications=notificationProvider==null?null:notificationProvider.getIfAvailable();
   for(var item:items){
    switch(item.action()){
     case "DELETE"->{
      if(item.scheduleId()==null)throw new BusinessException(ErrorCode.INVALID_INPUT);
      var s=schedules.lockOwned(userId,item.scheduleId()).orElseThrow(()->new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
      if(!contractId.equals(s.getContractId()))throw new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND);
      schedules.delete(s);
     }
     case "UPDATE"->{
      if(item.scheduleId()==null||item.date()==null||item.title()==null||item.title().isBlank())throw new BusinessException(ErrorCode.INVALID_INPUT);
      String t=item.title().strip();
      if(t.codePointCount(0,t.length())>50)throw new BusinessException(ErrorCode.INVALID_INPUT);
      var s=schedules.lockOwned(userId,item.scheduleId()).orElseThrow(()->new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
      if(!contractId.equals(s.getContractId()))throw new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND);
      LocalDate oldDate=s.getDate();
      s.updateTitle(t);
      if(item.candidateId()!=null){
       ScheduleCandidateInfo cand=candidateMap.get(item.candidateId());
       ScheduleSource src=cand.date()!=null&&cand.date().equals(item.date())&&cand.title().equals(t)?("REVIEW".equals(cand.source())?ScheduleSource.REVIEW:ScheduleSource.WRITTEN):ScheduleSource.MANUAL;
       s.updateFromCandidate(item.candidateId(),cand.dateKind(),s.getTitle(),item.date(),src,src==ScheduleSource.MANUAL?null:cand.location());
      }else{
       s.updateDate(item.date());
      }
      if(!item.date().equals(oldDate)&&notifications!=null)notifications.deleteUnreadForScheduleDate(s.getScheduleId(),oldDate);
      schedules.saveAndFlush(s);
     }
     case "CREATE"->{
      if(item.date()==null||item.title()==null||item.title().isBlank())throw new BusinessException(ErrorCode.INVALID_INPUT);
      String title=item.title().strip();
      if(title.codePointCount(0,title.length())>50)throw new BusinessException(ErrorCode.INVALID_INPUT);
      Long candId=item.candidateId();
      String dateKind=null;String location=null;ScheduleSource src=ScheduleSource.MANUAL;
      if(candId!=null){
       ScheduleCandidateInfo cand=candidateMap.get(candId);
       dateKind=cand.dateKind();
       if(cand.date()!=null&&cand.date().equals(item.date())&&cand.title().equals(title)){
        src="REVIEW".equals(cand.source())?ScheduleSource.REVIEW:ScheduleSource.WRITTEN;
        location=cand.location();
       }
      }
      schedules.saveAndFlush(Schedule.create(userId,contractId,candId,dateKind,title,item.date(),src,location));
     }
     default->throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
   }
  }
  return schedules.findByUserIdAndContractIdOrderByDateAscScheduleIdAsc(userId,contractId).stream()
   .map(s->new ConfirmComparisonInfo.SavedSchedule(s.getScheduleId(),s.getTitle(),s.getDate())).toList();
 }
 private LocalDate seoulToday(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
}
