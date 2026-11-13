package com.team.docai.domain.schedule.service;

import com.team.docai.domain.schedule.dto.*;
import com.team.docai.domain.schedule.entity.ContractSchedule;
import com.team.docai.domain.schedule.repository.ContractScheduleRepository;
import com.team.docai.domain.document.repository.DocumentCheckRepository;
import com.team.docai.domain.document.service.DocumentService;
import com.team.docai.domain.document.entity.*;
import com.team.docai.domain.document.dto.CheckInfo;
import com.team.docai.global.error.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ScheduleService {
    private final ContractScheduleRepository schedules;private final DocumentCheckRepository documents;private final DocumentService service;
    public List<ScheduleInfo> list(UUID user){return schedules.findByUserIdOrderByDateAsc(user).stream().map(ScheduleInfo::from).toList();}
    @Transactional public CheckInfo archive(UUID user,UUID id,ArchiveCommand c){
        if(id.equals(c.previousId()))throw new BusinessException(ErrorCode.COMPARISON_INVALID);
        // 이전본부터 잠가 동시에 같은 계약의 수정본 두 개가 확정되는 것을 방지합니다.
        DocumentCheck previous=c.previousId()==null?null:documents.lockOwned(c.previousId(),user).orElseThrow(()->new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        var d=documents.lockOwned(id,user).orElseThrow(()->new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        if(d.isArchived())return service.info(d); // 재시도는 일정 재삽입 없이 같은 결과 반환
        if(d.getStatus()!=CheckStatus.COMPLETED&&d.getStatus()!=CheckStatus.PARTIAL)throw new BusinessException(ErrorCode.DOCUMENT_NOT_READY);
        if(previous!=null){
            boolean hasRevision=documents.findByUserIdOrderByCreatedAtDesc(user).stream().anyMatch(x->previous.getId().equals(x.getPreviousId()));
            if(!c.sameContractConfirmed()||!previous.isArchived()||previous.getDocumentType()!=d.getDocumentType()||hasRevision)throw new BusinessException(ErrorCode.COMPARISON_INVALID);
            d.revise(previous.getId(),previous.getRevision()+1);
        }
        var suggestions=service.info(d).analysis().schedules();Set<String> seen=new HashSet<>();
        for(var item:c.schedules()){
            if(!seen.add(item.key())||item.date()==null)throw new BusinessException(ErrorCode.INVALID_INPUT);
            var suggestion=suggestions.stream().filter(s->s.key().equals(item.key())).findFirst().orElseThrow(()->new BusinessException(ErrorCode.INVALID_INPUT));
            String source=item.date().toString().equals(suggestion.date())?suggestion.source():"직접 입력";
            schedules.save(ContractSchedule.create(user,id,item.key(),suggestion.title(),item.date(),source));
        }
        if(previous!=null)schedules.deleteByDocumentIdAndUserId(previous.getId(),user);
        d.archive();return service.info(d);
    }
}
