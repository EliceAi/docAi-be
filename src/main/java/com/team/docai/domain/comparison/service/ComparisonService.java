package com.team.docai.domain.comparison.service;

import com.team.docai.domain.comparison.dto.ComparisonInfo;
import com.team.docai.domain.comparison.dto.ComparisonInfo.Change;
import com.team.docai.domain.document.service.DocumentService;
import com.team.docai.global.error.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ComparisonService {
    private final DocumentService documents;
    private final com.team.docai.domain.comparison.repository.ComparisonInputRepository inputs;
    private final com.team.docai.domain.document.repository.DocumentCheckRepository documentRepository;
    @Transactional public ComparisonInfo input(UUID user,com.team.docai.domain.comparison.dto.ComparisonInputCommand c){
        documentRepository.lockOwned(c.currentId(),user).orElseThrow(()->new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        var comparison=compare(user,c.previousId(),c.currentId());
        var change=comparison.changes().stream().filter(x->x.label().equals(c.label())).findFirst().orElseThrow(()->new BusinessException(ErrorCode.INVALID_INPUT));
        if(!change.status().equals("UNKNOWN")&&!change.source().equals("직접 입력"))throw new BusinessException(ErrorCode.INVALID_INPUT);
        var old=inputs.findByUserIdAndPreviousIdAndCurrentId(user,c.previousId(),c.currentId()).stream().filter(x->x.getLabel().equals(c.label())).findFirst();
        if(old.isPresent())old.get().correct(c.before(),c.after());else inputs.save(com.team.docai.domain.comparison.entity.ComparisonInput.create(user,c.previousId(),c.currentId(),c.label(),c.before(),c.after()));
        return compare(user,c.previousId(),c.currentId());
    }
    public ComparisonInfo compare(UUID user,UUID previousId,UUID currentId){
        var a=documents.get(user,previousId);var b=documents.get(user,currentId);
        if(a.id().equals(b.id())||a.documentType()!=b.documentType()||!a.archived()||b.archived()||a.analysis()==null||b.analysis()==null)throw new BusinessException(ErrorCode.COMPARISON_INVALID);
        Map<String,String> before=new LinkedHashMap<>(),after=new LinkedHashMap<>();
        a.analysis().conditions().forEach(x->before.put(x.label(),x.value()));b.analysis().conditions().forEach(x->after.put(x.label(),x.value()));
        a.analysis().clauses().forEach(x->before.put("조항: "+x.title(),x.original()));b.analysis().clauses().forEach(x->after.put("조항: "+x.title(),x.original()));
        Set<String> keys=new LinkedHashSet<>(before.keySet());keys.addAll(after.keySet());List<Change> changes=new ArrayList<>();
        for(String key:keys){String old=before.get(key),now=after.get(key);boolean unknown=old==null||now==null||old.contains("읽지 못함")||now.contains("읽지 못함");
            changes.add(new Change(key,old==null?"확인 불가":old,now==null?"확인 불가":now,unknown?"UNKNOWN":old.equals(now)?"SAME":"CHANGED","분석 결과 비교 · "+b.analysis().mode()));}
        for(var input:inputs.findByUserIdAndPreviousIdAndCurrentId(user,previousId,currentId)){
            for(int i=0;i<changes.size();i++)if(changes.get(i).label().equals(input.getLabel()))changes.set(i,new Change(input.getLabel(),input.getBeforeValue(),input.getAfterValue(),input.getBeforeValue().equals(input.getAfterValue())?"SAME":"CHANGED","직접 입력"));
        }
        return new ComparisonInfo(previousId,currentId,changes,a.analysis().partial()||b.analysis().partial()||a.pageCount()!=b.pageCount());
    }
}
