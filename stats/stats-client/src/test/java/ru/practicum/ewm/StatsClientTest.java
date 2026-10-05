package ru.practicum.ewm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
        context.registerBean(DiscoveryClient.class, () -> discoveryClient);
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
    void hitReportsMissingDiscoveryInstance() {
        when(discoveryClient.getInstances("stats-server")).thenReturn(List.of());
        var hit = new EndpointHitRequestDto("main-service", "/events/1", "127.0.0.1",
                LocalDateTime.of(2026, 10, 5, 10, 0));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.hit(hit));

        assertTrue(exception.getMessage().contains("stats-server"));
    }
}
