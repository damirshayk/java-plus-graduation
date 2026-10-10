package ru.practicum.ewm.stats.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.Channel;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelFactory;
import net.devh.boot.grpc.client.nameresolver.NameResolverRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import ru.practicum.ewm.stats.proto.collector.UserActionControllerGrpc;
import ru.practicum.ewm.stats.proto.dashboard.RecommendationsControllerGrpc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatsClientConfigurationTest {

    @Test
    void bindsDefaultPropertiesAndCreatesIndependentClientsFromNamedChannels() {
        Channel collectorChannel = mock(Channel.class);
        Channel analyzerChannel = mock(Channel.class);
        GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
        when(factory.createChannel("collector")).thenReturn(collectorChannel);
        when(factory.createChannel("analyzer")).thenReturn(analyzerChannel);
        try (var context = context(factory)) {
            context.refresh();

            StatsClientProperties properties = context.getBean(StatsClientProperties.class);
            assertThat(properties.getTimeoutMs()).isEqualTo(1000);
            assertThat(properties.getCircuitBreaker().getSlidingWindowSize()).isEqualTo(10);
            assertThat(properties.getCircuitBreaker().getMinimumNumberOfCalls()).isEqualTo(1);
            assertThat(properties.getCircuitBreaker().getFailureRateThreshold()).isEqualTo(50);
            assertThat(properties.getCircuitBreaker().getWaitDurationInOpenStateMs()).isEqualTo(5000);
            assertThat(properties.getCircuitBreaker().getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(1);
            assertThat(context.getBeanNamesForType(CollectorClient.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(AnalyzerClient.class)).hasSize(1);
            assertThat(context.getBean(UserActionControllerGrpc.UserActionControllerBlockingStub.class).getChannel())
                    .isSameAs(collectorChannel);
            assertThat(context.getBean(RecommendationsControllerGrpc.RecommendationsControllerBlockingStub.class)
                    .getChannel()).isSameAs(analyzerChannel);
            assertThat(context.getBeansOfType(CircuitBreaker.class)).hasSize(2);
        }
    }

    @Test
    void bindsEveryGrpcSettingAndIgnoresContractErrorsInCircuitBreaker() {
        try (var context = context(factory(), "stats.client.timeout-ms=1700",
                "stats.client.circuit-breaker.sliding-window-size=12",
                "stats.client.circuit-breaker.minimum-number-of-calls=3",
                "stats.client.circuit-breaker.failure-rate-threshold=75",
                "stats.client.circuit-breaker.wait-duration-in-open-state-ms=9000",
                "stats.client.circuit-breaker.permitted-number-of-calls-in-half-open-state=2")) {
            context.refresh();
            StatsClientProperties properties = context.getBean(StatsClientProperties.class);
            assertThat(properties.getTimeoutMs()).isEqualTo(1700);
            assertThat(properties.getCircuitBreaker().getSlidingWindowSize()).isEqualTo(12);
            assertThat(properties.getCircuitBreaker().getMinimumNumberOfCalls()).isEqualTo(3);
            assertThat(properties.getCircuitBreaker().getFailureRateThreshold()).isEqualTo(75);
            assertThat(properties.getCircuitBreaker().getWaitDurationInOpenStateMs()).isEqualTo(9000);
            assertThat(properties.getCircuitBreaker().getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
            assertThat(context.getBeansOfType(CircuitBreaker.class)).hasSize(2);
            for (CircuitBreaker breaker : context.getBeansOfType(CircuitBreaker.class).values()) {
                assertThat(breaker.getCircuitBreakerConfig().getRecordExceptionPredicate()
                        .test(io.grpc.Status.UNAVAILABLE.asRuntimeException())).isTrue();
                assertThat(breaker.getCircuitBreakerConfig().getIgnoreExceptionPredicate()
                        .test(io.grpc.Status.INVALID_ARGUMENT.asRuntimeException())).isTrue();
                assertThat(breaker.getCircuitBreakerConfig().getIgnoreExceptionPredicate()
                        .test(new IllegalStateException("Некорректный ответ"))).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stats.client.timeout-ms=0", "stats.client.timeout-ms=-1",
            "stats.client.circuit-breaker.sliding-window-size=0",
            "stats.client.circuit-breaker.minimum-number-of-calls=0",
            "stats.client.circuit-breaker.failure-rate-threshold=0",
            "stats.client.circuit-breaker.failure-rate-threshold=101",
            "stats.client.circuit-breaker.wait-duration-in-open-state-ms=0",
            "stats.client.circuit-breaker.permitted-number-of-calls-in-half-open-state=0"})
    void invalidPropertiesPreventStartup(String property) {
        try (var context = context(factory(), property)) {
            assertThatThrownBy(context::refresh).hasCauseInstanceOf(ConfigurationPropertiesBindException.class);
        }
    }

    private GrpcChannelFactory factory() {
        GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
        when(factory.createChannel("collector")).thenReturn(mock(Channel.class));
        when(factory.createChannel("analyzer")).thenReturn(mock(Channel.class));
        return factory;
    }

    private AnnotationConfigApplicationContext context(GrpcChannelFactory factory, String... properties) {
        var context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        context.registerBean(GrpcChannelFactory.class, () -> factory);
        context.registerBean("grpcNameResolverRegistration", NameResolverRegistration.class,
                () -> new NameResolverRegistration(List.of()), definition -> definition.setLazyInit(true));
        context.register(StatsClientConfiguration.class);
        return context;
    }
}
