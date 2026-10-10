package com.shylesh.reconciliation_service.client;

import com.shylesh.reconciliation_service.config.ReconciliationProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

/**
 * HTTP clients for the sources. The ledger, payment and payout APIs are called with an OAuth2
 * token for this service's own client (client credentials: obtained, cached and renewed by the
 * authorized-client manager, outside any user request); the processor takes its API key.
 */
@Configuration
public class ApiClientsConfig {

    static final String REGISTRATION_ID = "payflow";

    /** Reused threads for the HTTP clients' work (idle ones are dropped after a minute). */
    private static final ExecutorService HTTP_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "recon-http-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                                 OAuth2AuthorizedClientService clientService) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clientService);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    public RestClient ledgerRestClient(RestClient.Builder builder, ReconciliationProperties properties,
                                       OAuth2AuthorizedClientManager manager) {
        return withToken(builder.clone(), manager).baseUrl(properties.ledger().baseUrl()).build();
    }

    @Bean
    public RestClient paymentsRestClient(RestClient.Builder builder, ReconciliationProperties properties,
                                         OAuth2AuthorizedClientManager manager) {
        return withToken(builder.clone(), manager).baseUrl(properties.payments().baseUrl()).build();
    }

    @Bean
    public RestClient payoutsRestClient(RestClient.Builder builder, ReconciliationProperties properties,
                                        OAuth2AuthorizedClientManager manager) {
        return withToken(builder.clone(), manager).baseUrl(properties.payouts().baseUrl()).build();
    }

    @Bean
    public RestClient processorRestClient(RestClient.Builder builder, ReconciliationProperties properties) {
        return builder.clone()
                .requestFactory(requestFactory())
                .baseUrl(properties.processor().baseUrl())
                .defaultHeader("X-Api-Key", properties.processor().apiKey())
                .build();
    }

    private static RestClient.Builder withToken(RestClient.Builder builder, OAuth2AuthorizedClientManager manager) {
        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        // the job runs outside any request: tokens belong to this service, not to a caller
        interceptor.setPrincipalResolver(request -> new AnonymousAuthenticationToken(
                "reconciliation", "reconciliation-service", AuthorityUtils.createAuthorityList("ROLE_SERVICE")));
        return builder.requestFactory(requestFactory()).requestInterceptor(interceptor);
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        // an executor of its own: otherwise Spring writes each request body on a new thread
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(HTTP_EXECUTOR)
                .build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }
}
