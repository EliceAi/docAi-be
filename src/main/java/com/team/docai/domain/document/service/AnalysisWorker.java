package com.team.docai.domain.document.service;

import com.team.docai.domain.document.client.DocumentAiClient;
import com.team.docai.domain.document.dto.*;
import com.team.docai.domain.document.entity.*;
import com.team.docai.domain.document.repository.DocumentCheckRepository;
import com.team.docai.global.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.util.*;
@Component @RequiredArgsConstructor
public class AnalysisWorker {
    private final DocumentCheckRepository documents;private final DocumentAiClient ai;private final LocalPageStore files;private final ObjectMapper mapper;private final TransactionTemplate tx;
    @Async("analysisExecutor") @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void analyze(AnalysisRequested e){
        var input=tx.execute(s->{var d=documents.lockOwned(e.id(),e.userId()).orElseThrow();if(d.getStatus()!=CheckStatus.PENDING)return null;d.start();return new Input(d.getDocumentType(),d.getPageCount(),d.getScenario());});
        if(input==null)return;
        try {
            List<byte[]> pages=new ArrayList<>();for(int i=0;i<input.count();i++)pages.add(files.read(e.id(),i));
            var result=ai.analyze(input.type(),pages,input.scenario());String json=mapper.writeValueAsString(result);
            tx.executeWithoutResult(s->{var d=documents.lockOwned(e.id(),e.userId()).orElseThrow();d.complete(json,result.partial());});
        } catch(Exception ex){String message=ex instanceof BusinessException b?b.getMessage():"분석 처리 중 오류가 발생했습니다. 다시 시도해 주세요.";
            tx.executeWithoutResult(s->documents.lockOwned(e.id(),e.userId()).ifPresent(d->d.fail(message)));
        }
    }
    @EventListener(ApplicationReadyEvent.class) public void recover(){tx.executeWithoutResult(s->documents.findByStatusIn(List.of(CheckStatus.PENDING,CheckStatus.RUNNING)).forEach(d->d.fail("서버가 재시작되어 분석이 중단되었습니다. 다시 시도해 주세요.")));}
    private record Input(DocumentType type,int count,String scenario){}
}
