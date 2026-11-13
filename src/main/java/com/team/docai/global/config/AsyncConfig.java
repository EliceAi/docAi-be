package com.team.docai.global.config;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
@Configuration @EnableAsync
public class AsyncConfig {
    @Bean org.springframework.transaction.support.TransactionTemplate transactionTemplate(org.springframework.transaction.PlatformTransactionManager manager){
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }
    @Bean("analysisExecutor") public Executor analysisExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor(); e.setCorePoolSize(2); e.setMaxPoolSize(2); e.setQueueCapacity(20); e.setThreadNamePrefix("analysis-");
        e.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()); e.initialize(); return e;
    }
}
