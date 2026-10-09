package ru.practicum.ewm.client.user;

import feign.Client;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancedRetryFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClientsProperties;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.RetryableFeignBlockingLoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReadRetryConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("spring.cloud.loadbalancer.retry.retry-on-all-operations=false",
                    "spring.cloud.loadbalancer.retry.max-retries-on-same-service-instance=0",
                    "spring.cloud.loadbalancer.retry.max-retries-on-next-service-instance=1",
                    "spring.cloud.loadbalancer.retry.retryable-status-codes=500,502,503,504",
                    "spring.cloud.loadbalancer.retry.backoff.min-backoff=100ms");

    @Test
    void readRetryAdapterMustRemainDisabledUnlessExplicitlyEnabled() {
        runner.run(context -> assertThat(context).doesNotHaveBean(LoadBalancedRetryFactory.class));
    }

    @Test
    void blockingBackoffMustUseFrameworkPropertyInsteadOfIgnoringReactorOnlySetting() {
        runner.withPropertyValues("ewm.read-retry.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(LoadBalancedRetryFactory.class);
            var policy = context.getBean(LoadBalancedRetryFactory.class).createBackOffPolicy("user-service");
            assertThat(policy).isInstanceOf(FixedBackOffPolicy.class);
            assertThat(((FixedBackOffPolicy) policy).getBackOffPeriod()).isEqualTo(100);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void configuredTransientGetStatusesMustHaveExactlyTwoTransportAttempts(int status) {
        runner.withPropertyValues("ewm.read-retry.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(LoadBalancedRetryFactory.class);
            Client transport = mock(Client.class);
            Request request = request(Request.HttpMethod.GET);
            when(transport.execute(any(), any())).thenReturn(response(status, request), response(200, request));
            var client = client(context.getBean(LoadBalancedRetryFactory.class), transport,
                    context.getBean(LoadBalancerClientFactory.class));

            assertThat(client.execute(request, new Request.Options()).status()).isEqualTo(200);

            verify(transport, times(2)).execute(any(), any());
        });
    }

    @Test
    void connectionFailureMustHaveExactlyTwoTransportAttempts() {
        runner.withPropertyValues("ewm.read-retry.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(LoadBalancedRetryFactory.class);
            Client transport = mock(Client.class);
            Request request = request(Request.HttpMethod.GET);
            when(transport.execute(any(), any())).thenThrow(new IOException("Соединение потеряно"))
                    .thenReturn(response(200, request));
            var client = client(context.getBean(LoadBalancedRetryFactory.class), transport,
                    context.getBean(LoadBalancerClientFactory.class));

            assertThat(client.execute(request, new Request.Options()).status()).isEqualTo(200);

            verify(transport, times(2)).execute(any(), any());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404})
    void knownClientErrorsMustNotRetry(int status) {
        runner.withPropertyValues("ewm.read-retry.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(LoadBalancedRetryFactory.class);
            Client transport = mock(Client.class);
            Request request = request(Request.HttpMethod.GET);
            when(transport.execute(any(), any())).thenReturn(response(status, request));
            var client = client(context.getBean(LoadBalancedRetryFactory.class), transport,
                    context.getBean(LoadBalancerClientFactory.class));

            assertThat(client.execute(request, new Request.Options()).status()).isEqualTo(status);

            verify(transport).execute(any(), any());
        });
    }

    @Test
    void mutationMustNotRetryEvenOnConfiguredTransientStatus() {
        runner.withPropertyValues("ewm.read-retry.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(LoadBalancedRetryFactory.class);
            Client transport = mock(Client.class);
            Request request = request(Request.HttpMethod.POST);
            when(transport.execute(any(), any())).thenReturn(response(503, request));
            var client = client(context.getBean(LoadBalancedRetryFactory.class), transport,
                    context.getBean(LoadBalancerClientFactory.class));

            assertThat(client.execute(request, new Request.Options()).status()).isEqualTo(503);

            verify(transport).execute(any(), any());
        });
    }

    private RetryableFeignBlockingLoadBalancerClient client(LoadBalancedRetryFactory retryFactory, Client transport,
                                                           LoadBalancerClientFactory clientFactory) {
        LoadBalancerClient balancer = mock(LoadBalancerClient.class);
        var instance = new DefaultServiceInstance("user-1", "user-service", "localhost", 8080, false);
        when(balancer.choose(eq("user-service"), any())).thenReturn(instance);
        when(balancer.reconstructURI(eq(instance), any())).thenReturn(URI.create("http://localhost:8080/internal/users/1"));
        return new RetryableFeignBlockingLoadBalancerClient(transport, balancer, retryFactory, clientFactory, List.of());
    }

    private Request request(Request.HttpMethod method) {
        return Request.create(method, "http://user-service/internal/users/1", Map.of(), null,
                StandardCharsets.UTF_8, null);
    }

    private Response response(int status, Request request) {
        return Response.builder().status(status).request(request).headers(Map.of()).body("", StandardCharsets.UTF_8).build();
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "ru.practicum.ewm.client.user")
    @EnableConfigurationProperties(LoadBalancerClientsProperties.class)
    static class TestConfiguration {
        @Bean
        LoadBalancerClientFactory clientFactory(LoadBalancerClientsProperties properties) {
            return new LoadBalancerClientFactory(properties);
        }
    }
}
