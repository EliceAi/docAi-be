package com.team.docai.domain.document.service;

import com.team.docai.domain.document.dto.*;
import com.team.docai.domain.document.entity.*;
import com.team.docai.domain.document.repository.DocumentCheckRepository;
import com.team.docai.global.error.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class DocumentService {
    private final DocumentCheckRepository documents; private final LocalPageStore files; private final ObjectMapper mapper; private final ApplicationEventPublisher events;
    @Transactional public CheckInfo create(UUID user,CreateCheckCommand c) {
        var prior=documents.findByUserIdAndRequestKey(user,c.requestKey());if(prior.isPresent())return info(prior.get());
        if(!Set.of("normal","partial","failure","revised").contains(c.scenario()))throw new BusinessException(ErrorCode.INVALID_INPUT);
        UUID id=UUID.randomUUID(); files.save(id,c.pages());
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)files.delete(id);}
        });
        var d=documents.save(DocumentCheck.create(id,user,c.requestKey(),c.title().strip(),c.documentType(),c.pages().size(),c.scenario()));
        events.publishEvent(new AnalysisRequested(id,user));return info(d);
    }
    public List<CheckInfo> list(UUID user,boolean archived){return documents.findByUserIdOrderByCreatedAtDesc(user).stream().filter(d->!archived||d.isArchived()).map(this::info).toList();}
    public CheckInfo get(UUID user,UUID id){return info(owned(user,id));}
    public byte[] page(UUID user,UUID id,int page){var d=owned(user,id);if(page<0||page>=d.getPageCount())throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);return files.read(id,page);}
    @Transactional public CheckInfo retry(UUID user,UUID id) {
        var d=documents.lockOwned(id,user).orElseThrow(()->new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        if(d.getStatus()!=CheckStatus.FAILED)throw new BusinessException(ErrorCode.DOCUMENT_CONFLICT);d.retry();events.publishEvent(new AnalysisRequested(id,user));return info(d);
    }
    private DocumentCheck owned(UUID user,UUID id){return documents.findByIdAndUserId(id,user).orElseThrow(()->new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));}
    public CheckInfo info(DocumentCheck d){
        try{return new CheckInfo(d.getId(),d.getTitle(),d.getDocumentType(),d.getStatus(),d.getPageCount(),d.isArchived(),d.getRevision(),d.getPreviousId(),d.getResultJson()==null?null:mapper.readValue(d.getResultJson(),AnalysisInfo.class),d.getFailureReason(),d.getCreatedAt());}
        catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);}
    }
}
