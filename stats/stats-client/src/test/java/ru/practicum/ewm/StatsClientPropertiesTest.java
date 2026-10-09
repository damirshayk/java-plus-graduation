package ru.practicum.ewm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatsClientPropertiesTest {

    @Test
    void libraryConfigurationBindsOneSettingsBeanWithExistingDefaults() {
        try (var context = context()) {
            context.refresh();
            BeanWrapper settings = settings(context);
            assertEquals("stats-server", settings.getPropertyValue("serviceId"));
            assertEquals(2, settings.getPropertyValue("discovery.maxAttempts"));
            assertEquals(100L, settings.getPropertyValue("discovery.backoffMs"));
            assertEquals(1000L, settings.getPropertyValue("http.connectTimeoutMs"));
            assertEquals(2000L, settings.getPropertyValue("http.readTimeoutMs"));
            assertEquals(10, settings.getPropertyValue("circuitBreaker.slidingWindowSize"));
            assertEquals(1, settings.getPropertyValue("circuitBreaker.minimumNumberOfCalls"));
            assertEquals(50F, settings.getPropertyValue("circuitBreaker.failureRateThreshold"));
            assertEquals(5000L, settings.getPropertyValue("circuitBreaker.waitDurationInOpenStateMs"));
            assertEquals(1, settings.getPropertyValue("circuitBreaker.permittedNumberOfCallsInHalfOpenState"));
        }
    }

    @Test
    void existingKeysOverrideEveryNestedSetting() {
        try (var context = context(
                "stats.service-id=alternate-stats",
                "stats.discovery.max-attempts=4",
                "stats.discovery.backoff-ms=9",
                "stats.http.connect-timeout-ms=1100",
                "stats.http.read-timeout-ms=2200",
                "stats.circuit-breaker.sliding-window-size=12",
                "stats.circuit-breaker.minimum-number-of-calls=3",
                "stats.circuit-breaker.failure-rate-threshold=75",
                "stats.circuit-breaker.wait-duration-in-open-state-ms=9000",
                "stats.circuit-breaker.permitted-number-of-calls-in-half-open-state=2")) {
            context.refresh();
            BeanWrapper settings = settings(context);
            assertEquals("alternate-stats", settings.getPropertyValue("serviceId"));
            assertEquals(4, settings.getPropertyValue("discovery.maxAttempts"));
            assertEquals(9L, settings.getPropertyValue("discovery.backoffMs"));
            assertEquals(1100L, settings.getPropertyValue("http.connectTimeoutMs"));
            assertEquals(2200L, settings.getPropertyValue("http.readTimeoutMs"));
            assertEquals(12, settings.getPropertyValue("circuitBreaker.slidingWindowSize"));
            assertEquals(3, settings.getPropertyValue("circuitBreaker.minimumNumberOfCalls"));
            assertEquals(75F, settings.getPropertyValue("circuitBreaker.failureRateThreshold"));
            assertEquals(9000L, settings.getPropertyValue("circuitBreaker.waitDurationInOpenStateMs"));
            assertEquals(2, settings.getPropertyValue("circuitBreaker.permittedNumberOfCallsInHalfOpenState"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "stats.service-id=",
            "stats.discovery.max-attempts=0",
            "stats.discovery.backoff-ms=0",
            "stats.http.connect-timeout-ms=0",
            "stats.http.read-timeout-ms=-1",
            "stats.circuit-breaker.sliding-window-size=0",
            "stats.circuit-breaker.minimum-number-of-calls=0",
            "stats.circuit-breaker.failure-rate-threshold=0",
            "stats.circuit-breaker.failure-rate-threshold=101",
            "stats.circuit-breaker.wait-duration-in-open-state-ms=0",
            "stats.circuit-breaker.permitted-number-of-calls-in-half-open-state=0"
    })
    void invalidNestedSettingPreventsContextStartup(String property) {
        try (var context = context(property)) {
            assertThrows(ConfigurationPropertiesBindException.class, context::refresh);
        }
    }

    private AnnotationConfigApplicationContext context(String... properties) {
        Class<?> configuration = assertDoesNotThrow(() -> Class.forName("ru.practicum.ewm.StatsClientConfiguration"),
                "Библиотека статистики должна явно регистрировать сгруппированные настройки");
        var context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        context.register(configuration);
        return context;
    }

    private BeanWrapper settings(AnnotationConfigApplicationContext context) {
        Class<?> propertiesType = assertDoesNotThrow(() -> Class.forName("ru.practicum.ewm.StatsClientProperties"),
                "Настройки клиента статистики должны быть объединены в StatsClientProperties");
        assertEquals(1, context.getBeanNamesForType(propertiesType).length);
        return new BeanWrapperImpl(context.getBean(propertiesType));
    }
}
