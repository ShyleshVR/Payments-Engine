package com.shylesh.webhook_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class WebhookHttpConfig {

    @Bean
    public HttpClient merchantHttpClient(WebhookProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.delivery().connectTimeout())
                // A redirect could point at an internal address the target check never saw.
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Sends run here, not on the scheduler thread, so one slow merchant endpoint delays only
     * its own delivery. Shut down with the context (Spring infers shutdown()).
     */
    @Bean
    public ExecutorService webhookDeliveryExecutor(WebhookProperties properties) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "webhook-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(properties.delivery().workers(), threadFactory);
    }
}
