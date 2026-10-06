package ru.practicum.ewm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClientsProperties;
import org.springframework.cloud.loadbalancer.blocking.client.BlockingLoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StatsClientTest {

    private DiscoveryClient discoveryClient;
    private AnnotationConfigApplicationContext context;
    private MockRestServiceServer server;
    private StatsClient client;

    @BeforeEach
    void setUp() {
        discoveryClient = mock(DiscoveryClient.class);
        context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "spring.cloud.loadbalancer.cache.enabled=false");
        context.registerBean(DiscoveryClient.class, () -> discoveryClient);
        context.registerBean(LoadBalancerClientFactory.class,
                () -> new LoadBalancerClientFactory(new LoadBalancerClientsProperties()));
        context.registerBean(LoadBalancerClient.class,
                () -> new BlockingLoadBalancerClient(context.getBean(LoadBalancerClientFactory.class)));
        context.registerBean(RestTemplateBuilder.class, () -> new RestTemplateBuilder()
                .additionalCustomizers(rest -> server = MockRestServiceServer.bindTo(rest).build()));
        context.register(StatsClient.class);
        context.refresh();
        client = context.getBean(StatsClient.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void hitUsesCurrentDiscoveryInstanceForEveryRequest() {
        when(discoveryClient.getInstances("stats-server"))
                .thenReturn(List.of(new DefaultServiceInstance("first", "stats-server", "first-node", 9123, false)))
                .thenReturn(List.of(new DefaultServiceInstance("second", "stats-server", "second-node", 9456, false)));
        server.expect(requestTo("http://first-node:9123/hit"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
        server.expect(requestTo("http://second-node:9456/hit"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
        var hit = new EndpointHitRequestDto("main-service", "/events/1", "127.0.0.1",
                LocalDateTime.of(2026, 10, 5, 10, 0));

        client.hit(hit);
        client.hit(hit);

        server.verify();
        verify(discoveryClient, times(2)).getInstances("stats-server");
    }

    @Test
    void hitDistributesRequestsAcrossAvailableInstances() {
        when(discoveryClient.getInstances("stats-server"))
                .thenReturn(List.of(
                        new DefaultServiceInstance("first", "stats-server", "first-node", 9123, false),
                        new DefaultServiceInstance("second", "stats-server", "second-node", 9456, false)));
        List<URI> requestUris = new ArrayList<>();
        server.expect(ExpectedCount.times(4), method(HttpMethod.POST))
                .andExpect(request -> requestUris.add(request.getURI()))
                .andRespond(withSuccess());
        var hit = new EndpointHitRequestDto("main-service", "/events/1", "127.0.0.1",
                LocalDateTime.of(2026, 10, 5, 10, 0));

        for (int i = 0; i < 4; i++) {
            client.hit(hit);
        }

        server.verify();
        assertEquals(2L, requestUris.stream().filter(URI.create("http://first-node:9123/hit")::equals).count());
        assertEquals(2L, requestUris.stream().filter(URI.create("http://second-node:9456/hit")::equals).count());
    }

    @Test
    void getStatsEncodesQueryParametersOnce() {
        when(discoveryClient.getInstances("stats-server"))
                .thenReturn(List.of(new DefaultServiceInstance("stats", "stats-server", "stats-node", 9123, false)));
        URI expectedUri = URI.create("http://stats-node:9123/stats"
                + "?start=2026-10-05%2010%3A00%3A00&end=2026-10-05%2011%3A00%3A00"
                + "&uris=%2Fevents%2Fa%2Bb%3Ftag%3Da%26note%3Dhello%20world%2C%2Fevents%2F2&unique=true");
        server.expect(requestTo(expectedUri))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"app":"main-service","uri":"/events/1","hits":2}]
                        """, MediaType.APPLICATION_JSON));

        List<ViewStats> result = client.getStats(LocalDateTime.of(2026, 10, 5, 10, 0),
                LocalDateTime.of(2026, 10, 5, 11, 0),
                List.of("/events/a+b?tag=a&note=hello world", "/events/2"), true);

        assertEquals(List.of(new ViewStats("main-service", "/events/1", 2L)), result);
        server.verify();
    }

    @Test
    void hitSkipsMissingDiscoveryInstanceAfterLimitedAttempts() {
        when(discoveryClient.getInstances("stats-server")).thenReturn(List.of());

        assertDoesNotThrow(() -> client.hit(hitRequest()));

        verify(discoveryClient, times(3)).getInstances("stats-server");
        server.verify();
    }

    @Test
    void getStatsReturnsEmptyAfterLimitedDiscoveryAttempts() {
        when(discoveryClient.getInstances("stats-server")).thenReturn(List.of());

        assertEquals(List.of(), requestStats());

        verify(discoveryClient, times(3)).getInstances("stats-server");
        server.verify();
    }

    @Test
    void hitWaitsForDiscoveryAndSendsOnlyOnce() {
        when(discoveryClient.getInstances("stats-server"))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(new DefaultServiceInstance("stats", "stats-server", "stats-node", 9123, false)));
        server.expect(requestTo("http://stats-node:9123/hit"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        client.hit(hitRequest());

        verify(discoveryClient, times(3)).getInstances("stats-server");
        server.verify();
    }

    @Test
    void getStatsWaitsForDiscoveryAndReturnsActualViews() {
        when(discoveryClient.getInstances("stats-server"))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(new DefaultServiceInstance("stats", "stats-server", "stats-node", 9123, false)));
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"app":"main-service","uri":"/events/1","hits":2}]
                        """, MediaType.APPLICATION_JSON));

        assertEquals(List.of(new ViewStats("main-service", "/events/1", 2L)), requestStats());

        verify(discoveryClient, times(3)).getInstances("stats-server");
        server.verify();
    }

    @Test
    void hitSkipsTimeoutWithoutResendingRequest() {
        useAvailableInstance();
        server.expect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("Таймаут ответа")));

        assertDoesNotThrow(() -> client.hit(hitRequest()));

        verify(discoveryClient).getInstances("stats-server");
        server.verify();
    }

    @Test
    void getStatsReturnsEmptyOnConnectionFailure() {
        useAvailableInstance();
        server.expect(method(HttpMethod.GET))
                .andRespond(withException(new IOException("Соединение недоступно")));

        assertEquals(List.of(), requestStats());

        verify(discoveryClient).getInstances("stats-server");
        server.verify();
    }

    @Test
    void hitSkipsServerErrorWithoutResendingRequest() {
        useAvailableInstance();
        server.expect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertDoesNotThrow(() -> client.hit(hitRequest()));

        verify(discoveryClient).getInstances("stats-server");
        server.verify();
    }

    @Test
    void getStatsReturnsEmptyOnServerError() {
        useAvailableInstance();
        server.expect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(List.of(), requestStats());

        verify(discoveryClient).getInstances("stats-server");
        server.verify();
    }

    @Test
    void hitDoesNotHideClientError() {
        useAvailableInstance();
        server.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThrows(HttpClientErrorException.class, () -> client.hit(hitRequest()));

        verify(discoveryClient).getInstances("stats-server");
        server.verify();
    }

    @Test
    void getStatsDoesNotHideClientError() {
        useAvailableInstance();
        server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThrows(HttpClientErrorException.class, this::requestStats);

        verify(discoveryClient).getInstances("stats-server");
        server.verify();
    }

    @Test
    void getStatsDoesNotHideInvalidResponse() {
        useAvailableInstance();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThrows(RestClientException.class, this::requestStats);

        server.verify();
    }

    @Test
    void hitDoesNotRetryOrHideUnexpectedSelectionError() {
        LoadBalancerClient failingBalancer = mock(LoadBalancerClient.class);
        IllegalStateException failure = new IllegalStateException("Ошибка выбора экземпляра");
        when(failingBalancer.choose("stats-server")).thenThrow(failure);
        StatsClient failingClient = new StatsClient("stats-server", failingBalancer, new RestTemplateBuilder());

        assertEquals(failure, assertThrows(IllegalStateException.class, () -> failingClient.hit(hitRequest())));

        verify(failingBalancer).choose("stats-server");
    }

    private void useAvailableInstance() {
        when(discoveryClient.getInstances("stats-server"))
                .thenReturn(List.of(new DefaultServiceInstance("stats", "stats-server", "stats-node", 9123, false)));
    }

    private EndpointHitRequestDto hitRequest() {
        return new EndpointHitRequestDto("main-service", "/events/1", "127.0.0.1",
                LocalDateTime.of(2026, 10, 5, 10, 0));
    }

    private List<ViewStats> requestStats() {
        return client.getStats(LocalDateTime.of(2026, 10, 5, 10, 0),
                LocalDateTime.of(2026, 10, 5, 11, 0), List.of("/events/1"), true);
    }
}
