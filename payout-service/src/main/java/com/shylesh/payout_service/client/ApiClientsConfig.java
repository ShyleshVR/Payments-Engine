package com.shylesh.payout_service.client;

import com.shylesh.payout_service.config.PayoutProperties;

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
import java.time.Duration;

/**
 * HTTP clients. The ledger API is called with an OAuth2 token for this service's own client
 * (client credentials: obtained, cached and renewed by the authorized-client manager, outside any
 * user request); the bank takes its API key. Bank calls run outside any transaction, with short
 * timeouts: a slow answer is an unknown outcome, retried under the same Idempotency-Key.
 */
@Configuration
public class ApiClientsConfig {

    static final String REGISTRATION_ID = "payflow";

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                                 OAuth2AuthorizedClientService clientService) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clientService);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    public RestClient ledgerRestClient(RestClient.Builder builder, PayoutProperties properties,
                                       OAuth2AuthorizedClientManager manager) {
        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        // tokens belong to this service, not to whoever triggered the call
        interceptor.setPrincipalResolver(request -> new AnonymousAuthenticationToken(
                "payout", "payout-service", AuthorityUtils.createAuthorityList("ROLE_SERVICE")));
        return builder.clone()
                .requestFactory(requestFactory(Duration.ofSeconds(10)))
                .requestInterceptor(interceptor)
                .baseUrl(properties.ledger().baseUrl())
                .build();
    }

    @Bean
    public RestClient bankRestClient(RestClient.Builder builder, PayoutProperties properties) {
        return builder.clone()
                .requestFactory(requestFactory(Duration.ofSeconds(5)))
                .baseUrl(properties.bank().baseUrl())
                .defaultHeader("X-Api-Key", properties.bank().apiKey())
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
