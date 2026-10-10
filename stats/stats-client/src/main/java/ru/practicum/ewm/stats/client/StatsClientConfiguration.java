package ru.practicum.ewm.stats.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import ru.practicum.ewm.stats.proto.collector.UserActionControllerGrpc;
import ru.practicum.ewm.stats.proto.dashboard.RecommendationsControllerGrpc;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StatsClientProperties.class)
public class StatsClientConfiguration {

    // При ручном создании stub ленивый resolver должен зарегистрироваться раньше канала.
    @Bean
    @DependsOn("grpcNameResolverRegistration")
    public UserActionControllerGrpc.UserActionControllerBlockingStub collectorStub(GrpcChannelFactory factory) {
        return UserActionControllerGrpc.newBlockingStub(factory.createChannel("collector"));
    }

    @Bean
    @DependsOn("grpcNameResolverRegistration")
    public RecommendationsControllerGrpc.RecommendationsControllerBlockingStub analyzerStub(GrpcChannelFactory factory) {
        return RecommendationsControllerGrpc.newBlockingStub(factory.createChannel("analyzer"));
    }

    @Bean
    public CircuitBreaker collectorCircuitBreaker(StatsClientProperties properties) {
        return circuitBreaker("collector", properties);
    }

    @Bean
    public CircuitBreaker analyzerCircuitBreaker(StatsClientProperties properties) {
        return circuitBreaker("analyzer", properties);
    }

    @Bean
    public CollectorClient collectorClient(UserActionControllerGrpc.UserActionControllerBlockingStub stub,
                                           StatsClientProperties properties,
                                           @Qualifier("collectorCircuitBreaker") CircuitBreaker circuitBreaker) {
        return new CollectorClient(stub, properties, circuitBreaker);
    }

    @Bean
    public AnalyzerClient analyzerClient(RecommendationsControllerGrpc.RecommendationsControllerBlockingStub stub,
                                         StatsClientProperties properties,
                                         @Qualifier("analyzerCircuitBreaker") CircuitBreaker circuitBreaker) {
        return new AnalyzerClient(stub, properties, circuitBreaker);
    }

    private CircuitBreaker circuitBreaker(String name, StatsClientProperties properties) {
        StatsClientProperties.CircuitBreaker protection = properties.getCircuitBreaker();
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowSize(protection.getSlidingWindowSize())
                .minimumNumberOfCalls(protection.getMinimumNumberOfCalls())
                .failureRateThreshold(protection.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(protection.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(protection.getPermittedNumberOfCallsInHalfOpenState())
                .recordException(GrpcFailures::isTemporary)
                .ignoreException(error -> !GrpcFailures.isTemporary(error))
                .build());
    }
}
