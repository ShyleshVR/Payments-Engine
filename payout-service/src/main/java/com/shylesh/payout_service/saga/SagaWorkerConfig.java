package com.shylesh.payout_service.saga;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class SagaWorkerConfig {

    /** Saga steps run here, so a slow bank call ties up one thread and nothing else. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService sagaStepExecutor(SagaProperties properties) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "payout-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(properties.workers(), threadFactory);
    }
}
