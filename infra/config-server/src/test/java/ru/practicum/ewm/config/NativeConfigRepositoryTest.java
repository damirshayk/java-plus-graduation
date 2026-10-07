package ru.practicum.ewm.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.NativeEnvironmentRepository;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = ConfigServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class NativeConfigRepositoryTest {

    private final NativeEnvironmentRepository repository;
    private final Environment environment;

    @Autowired
    NativeConfigRepositoryTest(NativeEnvironmentRepository repository, Environment environment) {
        this.repository = repository;
        this.environment = environment;
    }

    @Test
    void eventServiceConfigurationIsLoadedFromFlatRepository() {
        Map<?, ?> properties = findServiceProperties("event-service");

        assertEquals("event-service", properties.get("spring.application.name"));
        assertEquals(0, properties.get("server.port"));
        assertEquals("classpath:db/event-migration", properties.get("spring.flyway.locations"));
        assertEquals("validate", properties.get("spring.jpa.hibernate.ddl-auto"));
        assertEquals(false, properties.get("spring.jpa.open-in-view"));
        assertEquals("stats-server", properties.get("stats.service-id"));
        assertEquals(2, properties.get("stats.discovery.max-attempts"));
        assertEquals(100, properties.get("stats.discovery.backoff-ms"));
        assertEquals(1000, properties.get("stats.http.connect-timeout-ms"));
        assertEquals(2000, properties.get("stats.http.read-timeout-ms"));
        assertEquals(10, properties.get("stats.circuit-breaker.sliding-window-size"));
        assertEquals(1, properties.get("stats.circuit-breaker.minimum-number-of-calls"));
        assertEquals(50, properties.get("stats.circuit-breaker.failure-rate-threshold"));
        assertEquals(5000, properties.get("stats.circuit-breaker.wait-duration-in-open-state-ms"));
        assertEquals(1, properties.get("stats.circuit-breaker.permitted-number-of-calls-in-half-open-state"));
        assertEquals(2, properties.get("ewm.display.retry.max-attempts"));
        assertEquals(100, properties.get("ewm.display.retry.backoff-ms"));
        assertEquals(10, properties.get("ewm.display.circuit-breaker.sliding-window-size"));
        assertEquals(2, properties.get("ewm.display.circuit-breaker.minimum-number-of-calls"));
        assertEquals(50, properties.get("ewm.display.circuit-breaker.failure-rate-threshold"));
        assertEquals(5000, properties.get("ewm.display.circuit-breaker.wait-duration-in-open-state-ms"));
        assertEquals(1, properties.get("ewm.display.circuit-breaker.permitted-number-of-calls-in-half-open-state"));
        assertEquals(2, properties.get("ewm.events.min-start-delay-hours"));
        assertEquals(false, properties.get("spring.cloud.loadbalancer.cache.enabled"));
        assertEquals(1000, properties.get("spring.cloud.openfeign.client.config.default.connectTimeout"));
        assertEquals(2000, properties.get("spring.cloud.openfeign.client.config.default.readTimeout"));
        assertTrue(properties.get("spring.datasource.url").toString().contains("mem:events"));
        assertFalse(properties.containsKey("spring.cloud.gateway.routes[0].uri"));
    }

    @Test
    void statsServerConfigurationIsLoadedFromFlatRepository() {
        Map<?, ?> properties = findServiceProperties("stats-server");

        assertTrue(properties.get("spring.datasource.url").toString().contains("mem:stats"));
        assertFalse(properties.containsKey("stats.service-id"));
        assertFalse(properties.containsKey("ewm.events.min-start-delay-hours"));
        assertFalse(properties.containsKey("spring.cloud.gateway.routes[0].uri"));
    }

    @Test
    void gatewayServerConfigurationIsLoadedFromFlatRepository() {
        Map<?, ?> properties = findServiceProperties("gateway-server");

        assertEquals(8080, properties.get("server.port"));
        assertEquals("lb://user-service", properties.get("spring.cloud.gateway.routes[0].uri"));
        assertEquals("Path=/admin/users/**", properties.get("spring.cloud.gateway.routes[0].predicates[0]"));
        assertEquals("lb://comment-service", properties.get("spring.cloud.gateway.routes[1].uri"));
        assertEquals("Path=/users/*/events/*/comments,/users/*/events/*/comments/**",
                properties.get("spring.cloud.gateway.routes[1].predicates[0]"));
        assertEquals("lb://request-service", properties.get("spring.cloud.gateway.routes[2].uri"));
        assertEquals("Path=/users/*/requests,/users/*/requests/**,/users/*/events/*/requests,/users/*/events/*/requests/**",
                properties.get("spring.cloud.gateway.routes[2].predicates[0]"));
        assertEquals("event-service", properties.get("spring.cloud.gateway.routes[3].id"));
        assertEquals("lb://event-service", properties.get("spring.cloud.gateway.routes[3].uri"));
        assertEquals("stats-server", properties.get("spring.cloud.gateway.routes[4].id"));
        assertEquals("lb://stats-server", properties.get("spring.cloud.gateway.routes[4].uri"));
        assertEquals("Path=/hit,/stats", properties.get("spring.cloud.gateway.routes[4].predicates[0]"));
        assertFalse(properties.containsKey("spring.cloud.gateway.routes[5].id"));
        assertFalse(properties.get("spring.cloud.gateway.routes[3].predicates[0]").toString().contains("/internal"));
        assertFalse(properties.get("spring.cloud.gateway.routes[2].predicates[0]").toString().contains("/internal"));
        assertFalse(properties.get("spring.cloud.gateway.routes[1].predicates[0]").toString().contains("/internal"));
        assertFalse(properties.containsKey("spring.datasource.url"));
        assertFalse(properties.containsKey("spring.datasource.username"));
        assertFalse(properties.containsKey("spring.datasource.password"));
        assertFalse(properties.containsKey("stats.service-id"));
        assertFalse(properties.containsKey("ewm.events.min-start-delay-hours"));
    }

    @Test
    void configServerEnvironmentDoesNotContainClientConfiguration() {
        assertEquals("config-server", environment.getProperty("spring.application.name"));
        assertEquals("0", environment.getProperty("server.port"));
        assertNull(environment.getProperty("spring.datasource.url"));
        assertNull(environment.getProperty("spring.datasource.username"));
        assertNull(environment.getProperty("spring.datasource.password"));
        assertNull(environment.getProperty("stats.service-id"));
        assertNull(environment.getProperty("ewm.events.min-start-delay-hours"));
        assertNull(environment.getProperty("spring.cloud.gateway.routes[0].uri"));
        assertNull(environment.getProperty("spring.cloud.gateway.routes[0].id"));
    }

    @Test
    void userServiceConfigurationHasOwnDatabaseAndBoundedTimeouts() {
        Map<?, ?> properties = findServiceProperties("user-service");

        assertTrue(properties.get("spring.datasource.url").toString().contains("mem:users"));
        assertEquals("classpath:db/user-migration", properties.get("spring.flyway.locations"));
        assertEquals(false, properties.get("spring.cloud.loadbalancer.cache.enabled"));
        assertEquals(1000, properties.get("spring.cloud.openfeign.client.config.default.connectTimeout"));
        assertEquals(2000, properties.get("spring.cloud.openfeign.client.config.default.readTimeout"));
        assertFalse(properties.containsKey("stats.service-id"));
        assertFalse(properties.containsKey("ewm.events.min-start-delay-hours"));
        assertFalse(properties.containsKey("spring.cloud.gateway.routes[0].uri"));
    }

    @Test
    void commentServiceConfigurationHasOwnDatabaseAndEventServiceDiscovery() {
        Map<?, ?> properties = findServiceProperties("comment-service");
        assertTrue(properties.get("spring.datasource.url").toString().contains("mem:comments"));
        assertEquals("classpath:db/comment-migration", properties.get("spring.flyway.locations"));
        assertEquals("event-service", properties.get("ewm.event-service-id"));
        assertEquals("validate", properties.get("spring.jpa.hibernate.ddl-auto"));
        assertEquals(false, properties.get("spring.jpa.open-in-view"));
        assertEquals(false, properties.get("spring.cloud.loadbalancer.cache.enabled"));
        assertEquals(1000, properties.get("spring.cloud.openfeign.client.config.default.connectTimeout"));
        assertEquals(2000, properties.get("spring.cloud.openfeign.client.config.default.readTimeout"));
        assertFalse(properties.containsKey("spring.cloud.gateway.routes[0].uri"));
    }

    @Test
    void unknownServiceHasNoPropertySources() {
        assertTrue(repository.findOne("unknown-service", "default", null).getPropertySources().isEmpty());
        assertTrue(repository.findOne("main-service", "default", null).getPropertySources().isEmpty());
    }

    @Test
    void requestServiceConfigurationHasOwnDatabaseAndEventServiceDiscovery() {
        Map<?, ?> properties = findServiceProperties("request-service");
        assertTrue(properties.get("spring.datasource.url").toString().contains("mem:requests"));
        assertEquals(0, properties.get("server.port"));
        assertEquals("classpath:db/request-migration", properties.get("spring.flyway.locations"));
        assertEquals("event-service", properties.get("ewm.event-service-id"));
        assertEquals("validate", properties.get("spring.jpa.hibernate.ddl-auto"));
        assertEquals(false, properties.get("spring.jpa.open-in-view"));
        assertEquals(false, properties.get("spring.cloud.loadbalancer.cache.enabled"));
        assertEquals(1000, properties.get("spring.cloud.openfeign.client.config.default.connectTimeout"));
        assertEquals(2000, properties.get("spring.cloud.openfeign.client.config.default.readTimeout"));
        assertFalse(properties.containsKey("spring.cloud.gateway.routes[0].uri"));
    }

    private Map<?, ?> findServiceProperties(String application) {
        List<PropertySource> sources = repository.findOne(application, "default", null).getPropertySources();

        assertEquals(1, sources.size());
        PropertySource source = sources.get(0);
        assertTrue(source.getName().contains("config-repo/" + application + ".yml"));
        return source.getSource();
    }
}
