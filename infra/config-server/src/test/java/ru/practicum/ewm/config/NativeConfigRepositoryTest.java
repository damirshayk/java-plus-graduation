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
    void mainServiceConfigurationIsLoadedFromFlatRepository() {
        Map<?, ?> properties = findServiceProperties("main-service");

        assertEquals("stats-server", properties.get("stats.service-id"));
        assertEquals(2, properties.get("ewm.events.min-start-delay-hours"));
        assertEquals(false, properties.get("spring.cloud.loadbalancer.cache.enabled"));
        assertTrue(properties.get("spring.datasource.url").toString().contains("mem:main"));
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
        assertEquals("lb://main-service", properties.get("spring.cloud.gateway.routes[0].uri"));
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
    void unknownServiceHasNoPropertySources() {
        assertTrue(repository.findOne("unknown-service", "default", null).getPropertySources().isEmpty());
    }

    private Map<?, ?> findServiceProperties(String application) {
        List<PropertySource> sources = repository.findOne(application, "default", null).getPropertySources();

        assertEquals(1, sources.size());
        PropertySource source = sources.get(0);
        assertTrue(source.getName().contains("config-repo/" + application + ".yml"));
        return source.getSource();
    }
}
