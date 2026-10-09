package ru.practicum.ewm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventDisplayPropertiesTest {

    @Test
    void groupedSettingsPreserveDefaults() {
        try (var context = context()) {
            context.refresh();
            BeanWrapper settings = settings(context);
            assertEquals(2, settings.getPropertyValue("retry.maxAttempts"));
            assertEquals(100L, settings.getPropertyValue("retry.backoffMs"));
            assertEquals(10, settings.getPropertyValue("circuitBreaker.slidingWindowSize"));
            assertEquals(2, settings.getPropertyValue("circuitBreaker.minimumNumberOfCalls"));
            assertEquals(50F, settings.getPropertyValue("circuitBreaker.failureRateThreshold"));
            assertEquals(5000L, settings.getPropertyValue("circuitBreaker.waitDurationInOpenStateMs"));
            assertEquals(1, settings.getPropertyValue("circuitBreaker.permittedNumberOfCallsInHalfOpenState"));
        }
    }

    @Test
    void existingKeysOverrideEveryNestedSetting() {
        try (var context = context(
                "ewm.display.retry.max-attempts=3",
                "ewm.display.retry.backoff-ms=7",
                "ewm.display.circuit-breaker.sliding-window-size=12",
                "ewm.display.circuit-breaker.minimum-number-of-calls=4",
                "ewm.display.circuit-breaker.failure-rate-threshold=75",
                "ewm.display.circuit-breaker.wait-duration-in-open-state-ms=9000",
                "ewm.display.circuit-breaker.permitted-number-of-calls-in-half-open-state=2")) {
            context.refresh();
            BeanWrapper settings = settings(context);
            assertEquals(3, settings.getPropertyValue("retry.maxAttempts"));
            assertEquals(7L, settings.getPropertyValue("retry.backoffMs"));
            assertEquals(12, settings.getPropertyValue("circuitBreaker.slidingWindowSize"));
            assertEquals(4, settings.getPropertyValue("circuitBreaker.minimumNumberOfCalls"));
            assertEquals(75F, settings.getPropertyValue("circuitBreaker.failureRateThreshold"));
            assertEquals(9000L, settings.getPropertyValue("circuitBreaker.waitDurationInOpenStateMs"));
            assertEquals(2, settings.getPropertyValue("circuitBreaker.permittedNumberOfCallsInHalfOpenState"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ewm.display.retry.max-attempts=0",
            "ewm.display.retry.backoff-ms=-1",
            "ewm.display.circuit-breaker.sliding-window-size=0",
            "ewm.display.circuit-breaker.minimum-number-of-calls=0",
            "ewm.display.circuit-breaker.failure-rate-threshold=0",
            "ewm.display.circuit-breaker.failure-rate-threshold=101",
            "ewm.display.circuit-breaker.wait-duration-in-open-state-ms=0",
            "ewm.display.circuit-breaker.permitted-number-of-calls-in-half-open-state=0"
    })
    void invalidNestedSettingPreventsContextStartup(String property) {
        try (var context = context(property)) {
            assertThrows(ConfigurationPropertiesBindException.class, context::refresh);
        }
    }

    private AnnotationConfigApplicationContext context(String... properties) {
        Class<?> propertiesType = propertiesType();
        var context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        ConfigurationPropertiesBindingPostProcessor.register(context);
        context.register(propertiesType);
        return context;
    }

    private BeanWrapper settings(AnnotationConfigApplicationContext context) {
        Class<?> propertiesType = propertiesType();
        assertEquals(1, context.getBeanNamesForType(propertiesType).length);
        return new BeanWrapperImpl(context.getBean(propertiesType));
    }

    private Class<?> propertiesType() {
        return assertDoesNotThrow(() -> Class.forName("ru.practicum.ewm.service.EventDisplayProperties"),
                "Настройки отображения должны быть объединены в EventDisplayProperties");
    }
}
